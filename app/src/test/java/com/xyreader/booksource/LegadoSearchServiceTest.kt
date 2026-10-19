package com.xyreader.booksource

import android.app.Application
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 多源搜索：解析、隔离失败、跳过不可用源、链接补全。全部合成页面，不联网。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoSearchServiceTest {

    private class FakeFetcher(private val pages: Map<String, String>) : SourcePageFetcher {
        val requested = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun fetch(url: String, headers: Map<String, String>): String {
            requested += url to headers
            pages.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
            throw IOException("没有为 $url 准备页面")
        }
    }

    private val pageAlpha = """
        <html><body><ul class="result">
          <li class="row"><a class="title" href="/book/1">合成书一</a><span class="author">甲</span></li>
          <li class="row"><a class="title" href="/book/2">合成书二</a><span class="author">乙</span></li>
        </ul></body></html>
    """.trimIndent()

    private val pageBeta = """
        <html><body><ul class="result">
          <li class="row"><a class="title" href="https://beta.example.com/book/9">合成书九</a><span class="author">丙</span></li>
          <li class="row"><span class="author">没有书名和链接的行</span></li>
        </ul></body></html>
    """.trimIndent()

    private fun source(
        url: String,
        name: String,
        enabled: Boolean = true,
        searchUrl: String? = "$url/search?q={{key}}",
        header: String? = null,
    ): LegadoBookSource {
        val json = buildString {
            append("""{"bookSourceName":"$name","bookSourceUrl":"$url","bookSourceType":0,"enabled":$enabled,""")
            if (searchUrl != null) append(""""searchUrl":"$searchUrl",""")
            if (header != null) append(""""header":"${header.replace("\"", "\\\"")}",""")
            append(""""ruleSearch":{"bookList":"class.result@tag.li","name":"class.title@text","bookUrl":"class.title@href","author":"class.author@text"}}""")
        }
        return LegadoBookSource.parseImport(json).single()
    }

    @Test fun searchesEnabledSourcesAndParsesRows() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha, "beta.example.com" to pageBeta))
        val service = LegadoSearchService(fetcher = fetcher)
        val results = service.search(
            listOf(source("https://alpha.example.com", "源A"), source("https://beta.example.com", "源B")),
            "合成关键词",
        )

        assertEquals(2, results.size)
        val alpha = results.single { it.sourceName == "源A" }
        assertNull(alpha.error)
        assertEquals(listOf("合成书一", "合成书二"), alpha.books.map { it.name })
        assertEquals(listOf("甲", "乙"), alpha.books.map { it.author })
        // 相对链接补全成绝对地址
        assertEquals("https://alpha.example.com/book/1", alpha.books[0].bookUrl)
        assertEquals("源A", alpha.books[0].sourceName)

        // 缺书名/缺链接的行被丢弃（第二条只有作者）
        val beta = results.single { it.sourceName == "源B" }
        assertEquals(listOf("合成书九"), beta.books.map { it.name })
        assertEquals("https://beta.example.com/book/9", beta.books[0].bookUrl)
    }

    @Test fun expandsKeywordIntoSearchUrl() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        LegadoSearchService(fetcher = fetcher)
            .search(listOf(source("https://alpha.example.com", "源A")), "合成 关键词")

        val (url, _) = fetcher.requested.single()
        assertTrue(url, url.startsWith("https://alpha.example.com/search?q="))
        assertTrue(url, url.contains(URLEncoder.encode("合成 关键词", "UTF-8")))
    }

    @Test fun forwardsSourceHeaders() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        LegadoSearchService(fetcher = fetcher).search(
            listOf(source("https://alpha.example.com", "源A", header = """{"User-Agent":"agent-x"}""")),
            "关键词",
        )
        assertEquals("agent-x", fetcher.requested.single().second["User-Agent"])
    }

    @Test fun failureOfOneSourceDoesNotAffectOthers() = runBlocking {
        // beta 没有准备页面 → 抛 IOException；alpha 正常
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        val results = LegadoSearchService(fetcher = fetcher).search(
            listOf(source("https://alpha.example.com", "源A"), source("https://beta.example.com", "源B")),
            "关键词",
        )

        val alpha = results.single { it.sourceName == "源A" }
        assertEquals(2, alpha.books.size)
        val beta = results.single { it.sourceName == "源B" }
        assertNotNull(beta.error)
        assertTrue(beta.books.isEmpty())
    }

    @Test fun pageWithoutMatchesReportsRuleMismatch() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to "<html><body>空页面</body></html>"))
        val results = LegadoSearchService(fetcher = fetcher)
            .search(listOf(source("https://alpha.example.com", "源A")), "关键词")
        assertTrue(results.single().error!!.contains("规则"))
    }

    @Test fun skipsDisabledAndUnsearchableSourcesWithoutFetching() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        val disabled = source("https://alpha.example.com", "停用源", enabled = false)
        val noSearch = source("https://gamma.example.com", "无搜索源", searchUrl = null)

        val results = LegadoSearchService(fetcher = fetcher).search(listOf(disabled, noSearch), "关键词")

        assertTrue(results.isEmpty())
        assertTrue("不可用的源不应发起请求", fetcher.requested.isEmpty())
    }

    @Test fun blankQueryNeverFetches() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        assertTrue(LegadoSearchService(fetcher = fetcher).search(listOf(source("https://alpha.example.com", "源A")), "  ").isEmpty())
        assertTrue(fetcher.requested.isEmpty())
    }

    @Test fun jsElementListBookListYieldsBooks() = runBlocking {
        // bookList 用 JS 返回节点集合（Legado 真实写法）；name/bookUrl/author 仍用 css
        val jsSource = LegadoBookSource.parseImport(
            """
            {"bookSourceName":"JS 节点源","bookSourceUrl":"https://alpha.example.com","enabled":true,
             "searchUrl":"https://alpha.example.com/search?q={{key}}",
             "ruleSearch":{"bookList":"@js:java.getElementList('class.result@tag.li')","name":"class.title@text","bookUrl":"class.title@href","author":"class.author@text"}}
            """.trimIndent(),
        ).single()
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        val results = LegadoSearchService(fetcher = fetcher).search(listOf(jsSource), "关键词")

        val result = results.single()
        assertNull(result.error)
        assertEquals(listOf("合成书一", "合成书二"), result.books.map { it.name })
        assertEquals(listOf("甲", "乙"), result.books.map { it.author })
        assertEquals("https://alpha.example.com/book/1", result.books[0].bookUrl)
    }

    @Test fun jsOnlyRulesYieldNoBooksRatherThanFakeResults() = runBlocking {
        // 页面正常，但规则是 JS（P4 未实现）→ 应报「没匹配到」而不是编造结果
        val jsSource = LegadoBookSource.parseImport(
            """
            {"bookSourceName":"JS 源","bookSourceUrl":"https://alpha.example.com","enabled":true,
             "searchUrl":"https://alpha.example.com/search?q={{key}}",
             "ruleSearch":{"bookList":"@js:java.getString('class.result@tag.li')","name":"class.title@text","bookUrl":"class.title@href"}}
            """.trimIndent(),
        ).single()
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha))
        val results = LegadoSearchService(fetcher = fetcher).search(listOf(jsSource), "关键词")
        assertTrue(results.single().books.isEmpty())
        assertNotNull(results.single().error)
    }
}
