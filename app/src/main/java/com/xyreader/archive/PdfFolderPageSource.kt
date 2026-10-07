package com.xyreader.archive

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.documentfile.provider.DocumentFile
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.Chapter
import com.xyreader.feedback.AppLog
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * PDF 合集页面源：同一个文件夹里的多个 PDF 合并成一本书，每个 PDF 是一章。
 *
 * 合并规则：book.uri 指向文件夹（与 [DirectoryPageSource] 同形），取其直接子项里的非隐藏 PDF，
 * 按文件名（去扩展名）自然排序、首尾相接——全书页码是各文件页数的累加，章节 = 文件，
 * 标题为文件名去扩展名（见 [PdfFolderMath]）。打开时逐个探测页数并固定（满足 pageCount
 * 不可变契约），探测完立即关闭，不长期占用文件描述符；损坏 / 加密 / 0 页的文件记日志后跳过。
 *
 * 子 PDF 按需打开：渲染某页时才用 [PdfPageSource] 打开它所在的文件，内部 LRU 最多同时保持
 * [MAX_OPEN_CHILDREN] 个。一本合集可能有几十个 PDF，全部常开会耗尽 fd 与 PdfRenderer 的内存；
 * 阅读以顺序翻页为主，只会在相邻两个文件间来回，2 个足够。
 *
 * 并发：PdfRenderer 是单线程 API，而 LRU 淘汰会关闭子源。用一把 [lock]（Mutex）把
 * 「取子源（必要时打开 / 淘汰）+ 子源渲染 / 读页尺寸」整体串行化，保证淘汰关闭不会撞上
 * 正在进行的渲染；map 的增删放在 synchronized(opened) 内，让非 suspend 的 [close]
 * 能安全地遍历关闭。
 */
class PdfFolderPageSource private constructor(
    private val appContext: Context,
    /** 合集里的 PDF，已按文件名自然序排好；打开后不再变化 */
    private val entries: List<Entry>,
) : AbstractPageSource() {

    /** 合集里的一个 PDF：文档 uri、章节标题（文件名去扩展名）与探测到的页数（≥ 1） */
    private class Entry(val uri: Uri, val title: String, val pageCount: Int)

    /** 各文件在全书中的起始页（前缀和），按页码定位文件时二分用 */
    private val startPages: IntArray = PdfFolderMath.startPages(entries.map { it.pageCount })

    override val cachedPageCount: Int = entries.sumOf { it.pageCount }

    override val chapters: List<Chapter> =
        PdfFolderMath.chapters(entries.map { it.title }, entries.map { it.pageCount })

    /**
     * 已打开的子 PDF（key = 文件序号）：访问序的 LinkedHashMap 充当 LRU。
     * 访问序下连 get 也会改动结构，所以一切读写都必须在 synchronized(opened) 内。
     */
    private val opened = LinkedHashMap<Int, PdfPageSource>(4, 0.75f, true)

    /** [close] 已执行；与 [opened] 同锁读写，防止关闭后又有迟到的打开把新子源塞回 map 而泄漏 fd */
    private var released = false

    /** 串行化「取子源 + 子源渲染」，见类说明 */
    private val lock = Mutex()

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        val (fileIndex, localPage) = PdfFolderMath.locate(startPages, cachedPageCount, index)
        return lock.withLock { childFor(fileIndex).renderPage(localPage) }
    }

    /** 委托给子源：PdfRenderer 能在解码位图前直接给出页面尺寸，连续列表不会先占位再跳动 */
    override suspend fun pageAspectRatio(index: Int): Float? {
        checkPage(index)
        val (fileIndex, localPage) = PdfFolderMath.locate(startPages, cachedPageCount, index)
        return lock.withLock { childFor(fileIndex).pageAspectRatio(localPage) }
    }

    /**
     * 取第 [fileIndex] 个文件的子源：命中则刷新 LRU 次序；未命中则在 IO 线程打开，
     * 并淘汰超出 [MAX_OPEN_CHILDREN] 的最久未用者。调用方必须已持有 [lock]。
     * 打开与入 map 在同一个 withContext 块内完成，协程恰在此时被取消也不会丢下一个没人关的子源。
     */
    private suspend fun childFor(fileIndex: Int): PdfPageSource = withContext(Dispatchers.IO) {
        synchronized(opened) { opened[fileIndex] }?.let { return@withContext it }

        val entry = entries[fileIndex]
        val child = PdfPageSource.open(
            appContext,
            BookEntity(
                title = entry.title,
                uri = entry.uri.toString(),
                format = BookFormat.PDF.name,
                addedAt = 0L,
            ),
        )
        val evicted = mutableListOf<PdfPageSource>()
        val accepted = synchronized(opened) {
            if (released) {
                false
            } else {
                opened[fileIndex] = child
                // 访问序 map 的迭代顺序是「最久未用 → 最近使用」，新子源在末尾，不会被自己淘汰
                val iterator = opened.values.iterator()
                while (opened.size > MAX_OPEN_CHILDREN && iterator.hasNext()) {
                    evicted += iterator.next()
                    iterator.remove()
                }
                true
            }
        }
        // 关闭只是释放 fd / renderer，放在 synchronized 之外，不占着 map 锁
        evicted.forEach { closeChild(it) }
        if (!accepted) {
            // close() 在打开期间已执行：不再收纳，回收后按「已关闭」报错
            closeChild(child)
            throw IllegalStateException("页面源已关闭")
        }
        child
    }

    private fun closeChild(child: PdfPageSource) {
        runCatching { child.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭合集子 PDF 异常", it) }
    }

    override fun close() = onFirstClose {
        // 锁内摘空 map 并置 released，再在锁外逐个关闭
        val toClose = synchronized(opened) {
            released = true
            opened.values.toList().also { opened.clear() }
        }
        toClose.forEach { closeChild(it) }
    }

    companion object {
        /** 同时保持打开的子 PDF 个数上限 */
        private const val MAX_OPEN_CHILDREN = 2

        /**
         * 打开 PDF 合集：book.uri 是文件夹的 document URI（同 [DirectoryPageSource.open]）。
         * 列出直接子项里的非隐藏 PDF，按文件名自然序排好后逐个探测页数；探测失败（损坏 / 加密）
         * 或 0 页的文件跳过，全部不可读时抛 [IllegalStateException]。
         * 探测要逐个打开文件，调用方应在 IO 线程调用（同 ArchiveFactory.open 的契约）。
         */
        fun open(context: Context, book: BookEntity): PdfFolderPageSource {
            val appContext = context.applicationContext
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(book.uri))
                ?: throw IllegalArgumentException("无效的目录 URI: ${book.uri}")

            // 每次 DocumentFile.name 都是一次 ContentResolver 查询，这里只取一次；
            // 排序键用章节标题（去扩展名），"Vol 1" 因此排在 "Vol 1 extra" 之前
            val pdfs = tree.listFiles()
                .mapNotNull { doc ->
                    val name = doc.name ?: return@mapNotNull null
                    if (PageSources.isExcludedEntry(name) ||
                        BookFormat.fromFileName(name) != BookFormat.PDF ||
                        doc.isDirectory
                    ) {
                        null
                    } else {
                        doc.uri to name.substringBeforeLast('.')
                    }
                }
                .sortedWith { a, b -> PageSources.naturalCompare(a.second, b.second) }

            val entries = ArrayList<Entry>(pdfs.size)
            for ((uri, title) in pdfs) {
                val pageCount = try {
                    probePageCount(appContext, uri)
                } catch (e: Exception) {
                    AppLog.w(PAGE_SOURCE_TAG, "跳过无法读取的 PDF: $uri", e)
                    continue
                }
                if (pageCount <= 0) {
                    AppLog.w(PAGE_SOURCE_TAG, "跳过没有页面的 PDF: $uri")
                    continue
                }
                entries += Entry(uri, title, pageCount)
            }
            if (entries.isEmpty()) {
                throw IllegalStateException("文件夹中没有可读的 PDF: ${book.uri}")
            }
            return PdfFolderPageSource(appContext, entries)
        }

        /**
         * 探测单个 PDF 的页数：打开 PdfRenderer 读取 pageCount 后立即关闭，不保留任何句柄。
         * 损坏 / 加密 / fd 不可 seek 的文件在 PdfRenderer 构造时抛异常，由调用方跳过。
         */
        private fun probePageCount(context: Context, uri: Uri): Int {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("无法打开 SAF 文档: $uri")
            try {
                val renderer = PdfRenderer(pfd)
                try {
                    return renderer.pageCount
                } finally {
                    // renderer.close() 会关闭它接管的 fd，下方 pfd.close() 幂等兜底
                    runCatching { renderer.close() }
                }
            } finally {
                runCatching { pfd.close() }
            }
        }
    }
}
