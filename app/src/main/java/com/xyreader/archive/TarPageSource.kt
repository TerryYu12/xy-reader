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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarFile
import java.io.FileInputStream

/**
 * CBT/TAR 页面源：commons-compress [TarFile]（非流式的 TarArchiveInputStream）。
 *
 * TarFile(SeekableByteChannel) 打开时会解析全部条目头，
 * 之后可按条目随机读取内容。
 */
class TarPageSource private constructor(
    private val tarFile: TarFile,
    private val pfd: ParcelFileDescriptor,
    imageEntries: List<TarArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（TarArchiveEntry.getName() 为全路径） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // TarFile 底层共享同一 channel，串行化读取保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { tarFile.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // TarFile.close() 关闭其持有的 channel（= fd），pfd.close() 幂等兜底
        runCatching { tarFile.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 TAR 归档异常", it) }
        runCatching { pfd.close() }
            .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭 ParcelFileDescriptor 异常", it) }
    }

    companion object {
        /** 打开 CBT/TAR */
        fun open(context: Context, book: BookEntity): TarPageSource {
            val pfd = PageSources.openPfd(context, book)
            try {
                val channel = FileInputStream(pfd.fileDescriptor).channel
                val tarFile = TarFile(channel)
                val entries = PageSources.collectImageEntries(
                    tarFile.entries.asSequence(),
                    isDirectory = { it.isDirectory },
                    nameOf = { it.name },
                )
                if (entries.isEmpty()) {
                    runCatching { tarFile.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "关闭空 TAR 归档异常", it) }
                    runCatching { pfd.close() }
                        .onFailure { Log.w(PAGE_SOURCE_TAG, "回收 pfd 异常", it) }
                    throw IllegalStateException("TAR 包内没有图片页面: ${book.uri}")
                }
                return TarPageSource(tarFile, pfd, entries)
            } catch (t: Throwable) {
                runCatching { pfd.close() }
                    .onFailure { Log.w(PAGE_SOURCE_TAG, "打开失败回收 pfd 异常", it) }
                throw t
            }
        }
    }
}
