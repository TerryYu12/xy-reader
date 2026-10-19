package com.xyreader.booksource

import android.content.Context
import com.xyreader.core.DownloadEntry
import com.xyreader.data.AppGraph
import com.xyreader.data.BookDownloadNaming
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「整本抓取」的结果送进书库：临时文件 → 校验通过 → 复用下载底座的命名与入库。
 *
 * 两条硬约定：
 * - **先落盘后入库**：抓取必须整本成功（[LegadoContentService.fetchBook] 保证），失败不入库、不留文件；
 * - **命名沿用下载底座**（来源 ID + 条目 ID + 版本 + 身份哈希）：同一本书重复下载得到同名文件、入库幂等复用记录，
 *   不同来源的同名书不会互相覆盖。
 */
open class LegadoBookDownloader(
    private val context: Context,
    private val service: LegadoContentService = LegadoContentService(),
    /** 入库入口；默认走全局仓库。测试注入假实现即可不碰 Room。 */
    private val importer: suspend (file: File, storedName: String, title: String) -> Long = { file, name, title ->
        AppGraph.libraryRepository(context).importDownloadedFile(file, name, title)
    },
) {

    /** 抓取整本并入库，返回书籍 id（可直接用于打开阅读器） */
    open suspend fun download(
        source: LegadoBookSource,
        book: SourceBook,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Long> = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "legado-fetch").apply { mkdirs() }
        val temp = File(workDir, "fetch-${System.currentTimeMillis()}.txt")
        try {
            when (val outcome = service.fetchBook(
                source = source,
                bookUrl = book.bookUrl,
                bookTitle = book.name,
                author = book.author,
                outFile = temp,
                onProgress = onProgress,
            )) {
                is BookFetchResult.Failed -> Result.failure(IllegalStateException(outcome.message))
                is BookFetchResult.Ok -> {
                    val storedName = BookDownloadNaming.storedFileName(
                        DownloadEntry(
                            sourceId = source.hostKey,
                            itemId = book.bookUrl,
                            title = book.name,
                            format = "TXT",
                            version = null,
                        ),
                        extension = "txt",
                    )
                    val bookId = importer(temp, storedName, book.name)
                    if (bookId <= 0) throw IllegalStateException("入库失败（未返回书籍 id）")
                    Result.success(bookId)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是「失败」：向上传播，让调用方（前台服务）走取消分支。
            // 临时文件仍由 finally 删除 —— 取消同样不留半本书、不入库。
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            // 书库私有副本是唯一事实来源，临时文件不保留
            runCatching { temp.delete() }
        }
    }
}
