package com.xyreader.booksource

import android.app.Application
import java.io.IOException
import java.net.URLEncoder
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
        var lastMethod: String = "GET"
        var lastBody: String? = null
        var lastCharset: String? = null

        override suspend fun fetch(
            url: String,
            headers: Map<String, String>,
            method: String,
            body: String?,
            charset: String?,
        ): FetchResponse {
            requested += url to headers
            lastMethod = method
            lastBody = body
            lastCharset = charset
            val match = pages.entries.firstOrNull { url.contains(it.key) }
            if (match != null) {
                return FetchResponse(match.value, url)
            }
            throw IOException("没有为 $url 准备页面")
        }

        override suspend fun fetch(url: String, headers: Map<String, String>): String =
            fetch(url, headers, "GET", null, null).body
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
            if (searchUrl != null) append(""""searchUrl":"${searchUrl.replace("\"", "\\\"")}",""")
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

    @Test fun searchProgressiveReportsProgressAndCollectsResults() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha, "beta.example.com" to pageBeta))
        val service = LegadoSearchService(fetcher = fetcher, concurrency = 1)
        val started = mutableListOf<String>()
        val completed = mutableListOf<Pair<String, Int>>()

        val results = service.searchProgressive(
            sources = listOf(
                source("https://alpha.example.com", "源A"),
                source("https://beta.example.com", "源B"),
            ),
            query = "合成关键词",
            onSourceStart = { src, idx, total ->
                started += "${src.name}:$idx/$total"
            },
            onSourceResult = { src, res, done, total ->
                completed += "${src.name}:$done/$total" to res.books.size
            },
        )

        assertEquals(2, results.size)
        assertEquals(listOf("源A:0/2", "源B:1/2"), started)
        assertEquals(listOf("源A:1/2" to 2, "源B:2/2" to 1), completed)
    }

    @Test fun searchProgressiveStopsOnCancellationAndPreservesPriorResults() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com" to pageAlpha, "beta.example.com" to pageBeta))
        val service = LegadoSearchService(fetcher = fetcher, concurrency = 1)
        val collectedResults = mutableListOf<SourceSearchResult>()

        val job = launch {
            service.searchProgressive(
                sources = listOf(
                    source("https://alpha.example.com", "源A"),
                    source("https://beta.example.com", "源B"),
                ),
                query = "关键词",
                onSourceResult = { _, result, _, _ ->
                    collectedResults.add(result)
                    // 在收到第一个源的结果后取消协程（模拟用户点击停止搜索）
                    cancel()
                },
            )
        }
        job.join()

        // 源A 已完成并保留在列表中；源B 因停止被跳过，没有发起多余请求
        assertEquals(1, collectedResults.size)
        assertEquals("源A", collectedResults[0].sourceName)
        assertEquals(2, collectedResults[0].books.size)
        assertTrue("源B 不应在取消后被请求", fetcher.requested.none { it.first.contains("beta.example.com") })
    }

    @Test fun supportsRelativeSearchUrl() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com/search.php" to pageAlpha))
        val s = source(
            url = "https://alpha.example.com",
            name = "相对路径源",
            searchUrl = "/search.php?key={{key}}",
        )
        val results = LegadoSearchService(fetcher = fetcher).search(listOf(s), "测试")
        assertEquals(1, results.size)
        assertEquals(2, results[0].books.size)
        val reqUrl = fetcher.requested.single().first
        assertEquals("https://alpha.example.com/search.php?key=%E6%B5%8B%E8%AF%95", reqUrl)
    }

    @Test fun supportsSearchUrlWithOptionsPostAndBody() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com/search" to pageAlpha))
        val s = source(
            url = "https://alpha.example.com",
            name = "POST源",
            searchUrl = "/search,{\"method\":\"POST\",\"body\":\"searchkey={{key}}&submit=\"}",
        )
        val results = LegadoSearchService(fetcher = fetcher).search(listOf(s), "凡人")
        assertEquals(1, results.size)
        assertEquals(2, results[0].books.size)
        assertEquals("POST", fetcher.lastMethod)
        assertEquals("searchkey=%E5%87%A1%E4%BA%BA&submit=", fetcher.lastBody)
        assertEquals("https://alpha.example.com/search", fetcher.requested.single().first)
    }

    @Test fun supportsSearchUrlWithCharsetGbk() = runBlocking {
        val fetcher = FakeFetcher(mapOf("alpha.example.com/search.php" to pageAlpha))
        val s = source(
            url = "https://alpha.example.com",
            name = "GBK源",
            searchUrl = "/search.php?key={{key}},{\"charset\":\"gbk\"}",
        )
        val results = LegadoSearchService(fetcher = fetcher).search(listOf(s), "斗破")
        assertEquals(1, results.size)
        assertEquals("gbk", fetcher.lastCharset)
        val expectedGbkEncoded = URLEncoder.encode("斗破", "gbk")
        assertTrue(fetcher.requested.single().first.contains(expectedGbkEncoded))
    }

    @Test fun parsesJsonSearchResults() = runBlocking {
        val jsonPayload = """
            {
              "status": 1,
              "data": [
                {"book_name": "星门", "book_url": "/book/101", "author_name": "老鹰吃小鸡"},
                {"book_name": "万族之劫", "book_url": "/book/102", "author_name": "老鹰吃小鸡"}
              ]
            }
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("api.example.com/search" to jsonPayload))
        val jsonSource = LegadoBookSource.parseImport(
            """
            {
              "bookSourceName": "JSON源",
              "bookSourceUrl": "https://api.example.com",
              "enabled": true,
              "searchUrl": "https://api.example.com/search?q={{key}}",
              "ruleSearch": {
                "bookList": "$.data[*]",
                "name": "book_name",
                "bookUrl": "book_url",
                "author": "author_name"
              }
            }
            """.trimIndent(),
        ).single()

        val results = LegadoSearchService(fetcher = fetcher).search(listOf(jsonSource), "老鹰")
        val res = results.single()
        assertNull(res.error)
        assertEquals(2, res.books.size)
        assertEquals("星门", res.books[0].name)
        assertEquals("https://api.example.com/book/101", res.books[0].bookUrl)
        assertEquals("老鹰吃小鸡", res.books[0].author)
        assertEquals("万族之劫", res.books[1].name)
    }

    @Test fun matchesDirectDetailPageOnExactSearch() = runBlocking {
        val detailPage = """
            <html><body>
              <div id="main">
                <h1 class="book-title">完美世界</h1>
                <span class="author">辰东</span>
                <p class="intro">一粒尘可填海，一根草斩尽日月星辰。</p>
              </div>
            </body></html>
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("alpha.example.com/search" to detailPage))
        val directSource = LegadoBookSource.parseImport(
            """
            {
              "bookSourceName": "直接重定向源",
              "bookSourceUrl": "https://alpha.example.com",
              "enabled": true,
              "searchUrl": "/search?q={{key}}",
              "ruleSearch": {
                "bookList": ".search-list-item",
                "name": "h1.book-title@text",
                "author": ".author@text",
                "intro": ".intro@text",
                "checkKeyWord": "h1.book-title@text"
              }
            }
            """.trimIndent(),
        ).single()

        val results = LegadoSearchService(fetcher = fetcher).search(listOf(directSource), "完美世界")
        val res = results.single()
        assertNull(res.error)
        assertEquals(1, res.books.size)
        assertEquals("完美世界", res.books[0].name)
        assertEquals("辰东", res.books[0].author)
        assertEquals("一粒尘可填海，一根草斩尽日月星辰。", res.books[0].intro)
    }

    @Test fun filtersOutUnrelatedRecommendationsWhenMatchingBooksExist() = runBlocking {
        val mixedPage = """
            <html><body><ul class="result">
              <li class="row"><a class="title" href="/book/1">三体：死神永生</a><span class="author">刘慈欣</span></li>
              <li class="row"><a class="title" href="/book/2">大唐Let's go</a><span class="author">小和尚</span></li>
              <li class="row"><a class="title" href="/book/3">三体</a><span class="author">刘慈欣</span></li>
              <li class="row"><a class="title" href="/book/4">马六甲架杆收费</a><span class="author">都市大王</span></li>
            </ul></body></html>
        """.trimIndent()
        val fetcher = FakeFetcher(mapOf("alpha.example.com/search" to mixedPage))
        val s = source("https://alpha.example.com", "测试源", searchUrl = "/search?q={{key}}")

        val results = LegadoSearchService(fetcher = fetcher).search(listOf(s), "三体")
        val res = results.single()
        assertNull(res.error)
        // 关键断言：排除了大唐与马六甲等推荐书，且完全匹配的《三体》排在最前
        assertEquals(2, res.books.size)
        assertEquals("三体", res.books[0].name)
        assertEquals("三体：死神永生", res.books[1].name)
    }
}
