package com.xyreader.archive

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.feedback.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.FileInputStream

/**
 * CB7/7Z 页面源：commons-compress [SevenZFile]。
 *
 * 与 Zip 相同策略：FileChannel 直接喂给 SevenZFile(SeekableByteChannel)，
 * 头部解析后随机访问各条目流。
 */
class SevenZipPageSource private constructor(
    private val sevenZip: SevenZFile,
    private val pfd: ParcelFileDescriptor,
    imageEntries: List<SevenZArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（SevenZArchiveEntry.name 为全路径） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // SevenZFile 底层共享同一 channel，串行化读取保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { sevenZip.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // SevenZFile.close() 关闭其持有的 channel（= fd），pfd.close() 幂等兜底
        runCatching { sevenZip.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭 7z 归档异常", it) }
        runCatching { pfd.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭 ParcelFileDescriptor 异常", it) }
    }

    companion object {
        /** 打开 CB7/7Z */
        fun open(context: Context, book: BookEntity): SevenZipPageSource {
            val pfd = PageSources.openPfd(context, book)
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val sevenZip = SevenZFile(channel)
                val entries = PageSources.collectImageEntries(
                    sevenZip.entries.asSequence(),
                    isDirectory = { it.isDirectory },
                    nameOf = { it.name },
                )
                if (entries.isEmpty()) {
                    runCatching { sevenZip.close() }
                        .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭空 7z 归档异常", it) }
                    runCatching { pfd.close() }
                        .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收 pfd 异常", it) }
                    throw IllegalStateException("7z 包内没有图片页面: ${book.uri}")
                }
                return SevenZipPageSource(sevenZip, pfd, entries)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                    .onFailure { AppLog.w(PAGE_SOURCE_TAG, "打开失败回收 pfd 异常", it) }
                throw t
            }
        }
    }
}
