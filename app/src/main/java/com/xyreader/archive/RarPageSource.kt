package com.xyreader.archive

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.feedback.AppLog
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * CBR/RAR 页面源：junrar。
 *
 * junrar 只接受本地 File：
 * - book.uri 是本地路径 → 直接用 File；
 * - content:// SAF → 先整包复制到 cacheDir/rar_cache（已存在则复用），再打开。
 */
class RarPageSource private constructor(
    private val archive: Archive,
    imageEntries: List<FileHeader>,
) : AbstractPageSource() {

    private val headers = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（displayPath 与排序一致，'\' 分隔符由 buildChapters 归一化） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(headers, nameOf = { it.displayPath() })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = headers.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // junrar 的 Archive 内部共享 RandomAccessFile，串行化读取保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { archive.getInputStream(headers[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        runCatching { archive.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭 RAR 归档异常", it) }
        // content URI 复制出的缓存文件有意保留在缓存目录，下次打开同一本书直接复用
    }

    companion object {
        /** 打开 CBR/RAR */
        fun open(context: Context, book: BookEntity): RarPageSource {
            val direct = PageSources.resolveLocalFile(book)
            val localFile = direct ?: PageSources.ensureRarCacheFile(context, book)
            val archive = Archive(localFile)
            val headers = PageSources.collectImageEntries(
                archive.fileHeaders.asSequence(),
                isDirectory = { it.isDirectory },
                nameOf = { it.displayPath() },
            )
            if (headers.isEmpty()) {
                runCatching { archive.close() }
                    .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭空 RAR 归档异常", it) }
                throw IllegalStateException("RAR 包内没有图片页面: ${book.uri}")
            }
            return RarPageSource(archive, headers)
        }
    }
}

/** RAR 条目路径：优先 Unicode 文件名（getFileNameW），为空回退 OEM 编码名（getFileNameString） */
private fun FileHeader.displayPath(): String {
    val w = fileNameW
    if (!w.isNullOrBlank()) return w
    return fileNameString ?: ""
}
