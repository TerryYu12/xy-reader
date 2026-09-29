package com.xyreader.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.FileInputStream

/**
 * CBZ/ZIP/EPUB 页面源。
 *
 * commons-compress [ZipFile] 支持 SeekableByteChannel 构造（FileChannel 即是），
 * 基于中央目录随机访问，无需整包解压。
 * EPUB 本质是 zip：只认图片条目的通用过滤规则天然兼容。
 */
class ZipPageSource private constructor(
    private val zip: ZipFile,
    private val pfd: ParcelFileDescriptor,
    imageEntries: List<ZipArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（ZipArchiveEntry.name 为含目录前缀的全路径） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // ZipFile 底层共享同一个 FileChannel，串行化读取保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { zip.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // ZipFile.close() 会顺带关闭传入的 channel（= 关闭 fd）；
        // pfd.close() 幂等兜底，重复关闭异常吞掉并记录
        runCatching { zip.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 ZipFile 异常", it) }
        runCatching { pfd.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 ParcelFileDescriptor 异常", it) }
    }

    companion object {
        /** 打开 CBZ/ZIP/EPUB：content:// 走 SAF 描述符，file:// 或本地路径直接打开 */
        fun open(context: Context, book: BookEntity): ZipPageSource {
            val pfd = PageSources.openPfd(context, book)
            try {
                // FileInputStream 包装 pfd 的既有 fd；channel 的生命周期由 ZipFile 管理，
                // fd 统一以 pfd.close() 收尾（幂等），避免双重关闭导致崩溃
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val zip = ZipFile(channel)
                val entries = PageSources.collectImageEntries(
                    zip.entries.iterator().asSequence(),
                    isDirectory = { it.isDirectory },
                    nameOf = { it.name },
                )
                if (entries.isEmpty()) {
                    runCatching { zip.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭空压缩包异常", it) }
                    runCatching { pfd.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "回收 pfd 异常", it) }
                    throw IllegalStateException("压缩包内没有图片页面: ${book.uri}")
                }
                return ZipPageSource(zip, pfd, entries)
            } catch (t: Throwable) {
                // 打开中途失败：回收 pfd（channel 若已被成功构造的 ZipFile 持有则随其 GC/关闭）
                runCatching { pfd.close() }
                    .onFailure { Log.w(PAGE_SOURCE_TAG, "打开失败回收 pfd 异常", it) }
                throw t
            }
        }
    }
}
