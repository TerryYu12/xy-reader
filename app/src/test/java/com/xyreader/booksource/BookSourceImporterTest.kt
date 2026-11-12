package com.xyreader.booksource

import android.app.Application
import android.net.Uri
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookSourceImporterTest {

    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val store get() = LegadoSourceStore(context)

    @Before
    fun clearStore() = runBlocking {
        store.clear()
    }

    private class FakeFetcher(private val pages: Map<String, String>) : SourcePageFetcher {
        var lastHeaders: Map<String, String>? = null

        override suspend fun fetch(url: String, headers: Map<String, String>): String {
            lastHeaders = headers
            pages[url]?.let { return it }
            throw IOException("404 Not Found")
        }
    }

    private val sampleJson = """
        [
          {"bookSourceName":"源A","bookSourceUrl":"https://a.example.com","bookSourceType":0,"enabled":true},
          {"bookSourceName":"源B","bookSourceUrl":"https://b.example.com","bookSourceType":0,"enabled":true}
        ]
    """.trimIndent()

    private val singleJson = """
        {"bookSourceName":"源C","bookSourceUrl":"https://c.example.com","bookSourceType":0,"enabled":true}
    """.trimIndent()

    @Test
    fun isUrlDetectsHttpAndHttps() {
        assertTrue(BookSourceImporter.isUrl("http://example.com/sources.json"))
        assertTrue(BookSourceImporter.isUrl("https://example.com/sources.json"))
        assertTrue(BookSourceImporter.isUrl("  HTTPS://example.com/sources.json  "))
        assertFalse(BookSourceImporter.isUrl("[{\"name\":\"test\"}]"))
        assertFalse(BookSourceImporter.isUrl("ftp://example.com"))
        assertFalse(BookSourceImporter.isUrl(""))
        assertFalse(BookSourceImporter.isUrl("   "))
    }

    @Test
    fun importFromTextParsesDirectJson() = runBlocking {
        val importer = BookSourceImporter(store)

        val result = importer.importFromText(sampleJson)
        assertTrue(result is ImportResult.Success)
        val success = result as ImportResult.Success
        assertEquals(2, success.sources.size)
        assertEquals(2, success.addedCount)
        assertEquals(2, store.sources.first().size)

        // 单条对象也能导入
        val singleResult = importer.importFromText(singleJson)
        assertTrue(singleResult is ImportResult.Success)
        assertEquals(1, (singleResult as ImportResult.Success).addedCount)
        assertEquals(3, store.sources.first().size)
    }

    @Test
    fun importFromTextHandlesEmptyOrInvalid() = runBlocking {
        val importer = BookSourceImporter(store)

        val emptyRes = importer.importFromText("   ")
        assertTrue(emptyRes is ImportResult.Failure)
        assertEquals("输入内容为空", (emptyRes as ImportResult.Failure).message)

        val invalidRes = importer.importFromText("not a json at all")
        assertTrue(invalidRes is ImportResult.Failure)
        assertTrue((invalidRes as ImportResult.Failure).message.contains("没有解析到有效的书源"))
    }

    @Test
    fun importFromUrlFetchesAndImports() = runBlocking {
        val url = "https://example.com/sources.json"
        val fakeFetcher = FakeFetcher(mapOf(url to sampleJson))
        val importer = BookSourceImporter(store, fakeFetcher)

        val result = importer.importFromUrl(url)
        assertTrue(result is ImportResult.Success)
        assertEquals(2, (result as ImportResult.Success).sources.size)
        assertEquals(2, store.sources.first().size)
        assertEquals(BookSourceImporter.DEFAULT_USER_AGENT, fakeFetcher.lastHeaders?.get("User-Agent"))
    }

    @Test
    fun importFromTextWithUrlTriggersNetworkFetch() = runBlocking {
        val url = "https://example.com/auto.json"
        val fakeFetcher = FakeFetcher(mapOf(url to sampleJson))
        val importer = BookSourceImporter(store, fakeFetcher)

        // importFromText 传入 URL 时自动转发到 importFromUrl
        val result = importer.importFromText("  $url  ")
        assertTrue(result is ImportResult.Success)
        assertEquals(2, (result as ImportResult.Success).sources.size)
        assertEquals(2, store.sources.first().size)
    }

    @Test
    fun importFromUrlHandlesFailures() = runBlocking {
        val fakeFetcher = FakeFetcher(emptyMap())
        val importer = BookSourceImporter(store, fakeFetcher)

        // 404 / 异常
        val failRes = importer.importFromUrl("https://example.com/notfound.json")
        assertTrue(failRes is ImportResult.Failure)
        assertTrue((failRes as ImportResult.Failure).message.contains("网络抓取失败"))

        // 空内容
        val emptyFetcher = FakeFetcher(mapOf("https://example.com/empty.json" to "   "))
        val emptyImporter = BookSourceImporter(store, emptyFetcher)
        val emptyRes = emptyImporter.importFromUrl("https://example.com/empty.json")
        assertTrue(emptyRes is ImportResult.Failure)
        assertTrue((emptyRes as ImportResult.Failure).message.contains("内容为空"))

        // 非合规书源
        val htmlFetcher = FakeFetcher(mapOf("https://example.com/page.html" to "<html><body>hello</body></html>"))
        val htmlImporter = BookSourceImporter(store, htmlFetcher)
        val htmlRes = htmlImporter.importFromUrl("https://example.com/page.html")
        assertTrue(htmlRes is ImportResult.Failure)
        assertTrue((htmlRes as ImportResult.Failure).message.contains("没有有效的 Legado 书源规则"))
    }

    @Test
    fun importFromStreamReadsAndImports() = runBlocking {
        val importer = BookSourceImporter(store)
        val stream = ByteArrayInputStream(sampleJson.toByteArray(Charsets.UTF_8))

        val result = importer.importFromStream(stream)
        assertTrue(result is ImportResult.Success)
        assertEquals(2, (result as ImportResult.Success).sources.size)
        assertEquals(2, store.sources.first().size)

        // 空流
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        val emptyRes = importer.importFromStream(emptyStream)
        assertTrue(emptyRes is ImportResult.Failure)
        assertTrue((emptyRes as ImportResult.Failure).message.contains("内容为空"))
    }

    @Test
    fun importFromUriReadsLocalFile() = runBlocking {
        val tempFile = File(context.cacheDir, "test_sources.json").apply {
            FileOutputStream(this).use { it.write(sampleJson.toByteArray(Charsets.UTF_8)) }
        }
        val uri = Uri.fromFile(tempFile)

        val importer = BookSourceImporter(store)
        val result = importer.importFromUri(context, uri)

        assertTrue(result is ImportResult.Success)
        assertEquals(2, (result as ImportResult.Success).sources.size)
        assertEquals(2, store.sources.first().size)
    }
}
