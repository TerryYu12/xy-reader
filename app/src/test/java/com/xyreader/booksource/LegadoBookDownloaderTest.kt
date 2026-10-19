package com.xyreader.booksource

import android.app.Application
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 整本下载 → 入库（上架）：命名沿用下载底座、失败不留文件、重复下载幂等。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoBookDownloaderTest {

    private class FakeFetcher(private val pages: Map<String, String>) : SourcePageFetcher {
        override suspend fun fetch(url: String, headers: Map<String, String>): String =
            pages[url] ?: throw IOException("没有为 $url 准备页面")
    }

    /** 注意：要匹配产品侧的 suspend 函数类型，普通函数类型不能赋给它 */
    private class FakeImporter : suspend (File, String, String) -> Long {
        val calls = mutableListOf<Triple<File, String, String>>()
        /** 入库那一刻文件是否真实存在（下载器会在入库后清理临时文件，事后再查必为假） */
        val existedAtImport = mutableListOf<Boolean>()
        var nextId = 42L
        override suspend fun invoke(file: File, storedName: String, title: String): Long {
            existedAtImport += file.exists() && file.length() > 0
            calls += Triple(file, storedName, title)
            return nextId
        }
    }

    private val source = LegadoBookSource.parseImport(
        """
        {"bookSourceName":"合成书源","bookSourceUrl":"https://novel.example.com","bookSourceType":0,"enabled":true,
         "ruleBookInfo":{"tocUrl":"class.toc-link@href"},
         "ruleToc":{"chapterList":"class.chapter-list@tag.a","chapterName":"text","chapterUrl":"href"},
         "ruleContent":{"content":"class.content@html"}}
        """.trimIndent(),
    ).single()

    private val book = SourceBook(
        name = "合成书名",
        bookUrl = "https://novel.example.com/book/1",
        sourceUrl = source.url,
        sourceName = source.name,
        author = "合成作者",
    )

    private fun pages(): Map<String, String> = mapOf(
        "https://novel.example.com/book/1" to """<html><body><a class="toc-link" href="/book/1/toc">目录</a></body></html>""",
        "https://novel.example.com/book/1/toc" to
            """<html><body><div class="chapter-list"><a href="/book/1/c1">第一章</a><a href="/book/1/c2">第二章</a></div></body></html>""",
        "https://novel.example.com/book/1/c1" to """<html><body><div class="content">正文一</div></body></html>""",
        "https://novel.example.com/book/1/c2" to """<html><body><div class="content">正文二</div></body></html>""",
    )

    private fun fetchDir(): File = File(RuntimeEnvironment.getApplication().cacheDir, "legado-fetch")

    @Test fun downloadsAndImportsWithIdentityBasedName() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = FakeImporter()
        val downloader = LegadoBookDownloader(
            context = context,
            service = LegadoContentService(fetcher = FakeFetcher(pages())),
            importer = importer,
        )

        val result = downloader.download(source, book)

        assertEquals(42L, result.getOrThrow())
        val (file, storedName, title) = importer.calls.single()
        assertEquals("合成书名", title)
        assertTrue(storedName, storedName.startsWith("novel.example.com_"))
        assertTrue(storedName, storedName.endsWith(".txt"))
        assertTrue("入库那一刻文件必须真实存在且有内容", importer.existedAtImport.single())
        assertEquals("临时文件应被清理", emptyList<File>(), fetchDir().listFiles()?.toList() ?: emptyList<File>())
    }

    @Test fun failedFetchNeverImportsAndLeavesNoFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = FakeImporter()
        val brokenPages = pages().toMutableMap().apply { remove("https://novel.example.com/book/1/c2") }
        val downloader = LegadoBookDownloader(
            context = context,
            service = LegadoContentService(fetcher = FakeFetcher(brokenPages)),
            importer = importer,
        )

        val result = downloader.download(source, book)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("第 2/2 章"))
        assertTrue("失败不得入库", importer.calls.isEmpty())
        assertEquals(emptyList<File>(), fetchDir().listFiles()?.toList() ?: emptyList<File>())
    }

    @Test fun repeatedDownloadOfSameBookUsesSameStoredName() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = FakeImporter()
        val downloader = LegadoBookDownloader(
            context = context,
            service = LegadoContentService(fetcher = FakeFetcher(pages())),
            importer = importer,
        )

        downloader.download(source, book)
        downloader.download(source, book)

        assertEquals(2, importer.calls.size)
        assertEquals(importer.calls[0].second, importer.calls[1].second)
    }

    @Test fun cancelledFetchNeverImportsAndLeavesNoFiles() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = FakeImporter()
        val firstChapterFetched = CompletableDeferred<Unit>()
        // 第二章永远拿不到：抓取会停在第二章，等测试取消
        val blockingFetcher = object : SourcePageFetcher {
            override suspend fun fetch(url: String, headers: Map<String, String>): String {
                if (url.endsWith("/c2")) return awaitCancellation()
                return pages()[url] ?: throw IOException("没有为 $url 准备页面")
            }
        }
        val downloader = LegadoBookDownloader(
            context = context,
            service = LegadoContentService(fetcher = blockingFetcher),
            importer = importer,
        )

        val job = launch(Dispatchers.Default) {
            downloader.download(source, book) { done, _ -> if (done >= 1) firstChapterFetched.complete(Unit) }
        }
        firstChapterFetched.await()
        job.cancelAndJoin()

        assertTrue("取消不得入库", importer.calls.isEmpty())
        assertEquals("取消不得留下半本书文件", emptyList<File>(), fetchDir().listFiles()?.toList() ?: emptyList<File>())
    }

    @Test fun differentSourcesWithSameTitleDoNotCollide() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val importer = FakeImporter()
        val downloader = LegadoBookDownloader(
            context = context,
            service = LegadoContentService(fetcher = FakeFetcher(pages())),
            importer = importer,
        )
        val otherSource = source.copy(url = "https://other.example.com", name = "另一个源")

        downloader.download(source, book)
        downloader.download(otherSource, book.copy(sourceUrl = otherSource.url, sourceName = otherSource.name))

        assertFalse("同名不同来源不能落到同一个文件名", importer.calls[0].second == importer.calls[1].second)
    }
}
