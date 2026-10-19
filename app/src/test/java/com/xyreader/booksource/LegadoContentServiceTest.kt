package com.xyreader.booksource

import android.app.Application
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 正文链路：目录（含翻页）、正文（含 nextContentUrl 拼接与净化）、整本合成。全部合成页面。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoContentServiceTest {

    private class FakeFetcher(private val pages: Map<String, String>) : SourcePageFetcher {
        val requested = mutableListOf<String>()
        override suspend fun fetch(url: String, headers: Map<String, String>): String {
            requested += url
            // 精确匹配优先，其次前缀匹配（便于给同一路径的不同 query 分别提供页面）
            pages[url]?.let { return it }
            pages.entries.firstOrNull { url.startsWith(it.key) }?.let { return it.value }
            throw IOException("没有为 $url 准备页面")
        }
    }

    private val source = LegadoBookSource.parseImport(
        """
        {"bookSourceName":"合成书源","bookSourceUrl":"https://novel.example.com","bookSourceType":0,"enabled":true,
         "searchUrl":"https://novel.example.com/search?q={{key}}",
         "ruleSearch":{"bookList":"class.result@tag.li","name":"class.title@text","bookUrl":"class.title@href"},
         "ruleBookInfo":{"tocUrl":"class.toc-link@href"},
         "ruleToc":{"chapterList":"class.chapter-list@tag.a","chapterName":"text","chapterUrl":"href","nextTocUrl":"class.next-page@href"},
         "ruleContent":{"content":"class.content@html","nextContentUrl":"class.next-content@href","replaceRegex":"##广告：.*"}}
        """.trimIndent(),
    ).single()

    private val detailPage = """<html><body><a class="toc-link" href="/book/1/toc">目录</a></body></html>"""
    private val tocPage1 = """
        <html><body><div class="chapter-list">
          <a href="/book/1/c1">第一章 起点</a><a href="/book/1/c2">第二章 转折</a>
        </div><a class="next-page" href="/book/1/toc?p=2">下一页</a></body></html>
    """.trimIndent()
    private val tocPage2 = """<html><body><div class="chapter-list"><a href="/book/1/c3">第三章 收束</a></div></body></html>"""
    private val chapter1 = """<html><body><div class="content">第一章正文<b>加粗</b>广告：买买买</div></body></html>"""
    private val chapter2 = """<html><body><div class="content">前半段</div><a class="next-content" href="/book/1/c2?p=2">下一节</a></body></html>"""
    private val chapter2Page2 = """<html><body><div class="content">后半段</div></body></html>"""
    private val chapter3 = """<html><body><div class="content">第三章正文</div></body></html>"""

    private fun fullPages(): Map<String, String> = mapOf(
        "https://novel.example.com/book/1" to detailPage,
        "https://novel.example.com/book/1/toc?p=2" to tocPage2,
        "https://novel.example.com/book/1/toc" to tocPage1,
        "https://novel.example.com/book/1/c1" to chapter1,
        "https://novel.example.com/book/1/c2?p=2" to chapter2Page2,
        "https://novel.example.com/book/1/c2" to chapter2,
        "https://novel.example.com/book/1/c3" to chapter3,
    )

    @Test fun readsChaptersFromDetailPageAndPaginatedToc() = runBlocking {
        val service = LegadoContentService(fetcher = FakeFetcher(fullPages()))
        val chapters = service.chapters(source, "https://novel.example.com/book/1").getOrThrow()

        assertEquals(listOf("第一章 起点", "第二章 转折", "第三章 收束"), chapters.map { it.name })
        assertEquals(
            listOf(
                "https://novel.example.com/book/1/c1",
                "https://novel.example.com/book/1/c2",
                "https://novel.example.com/book/1/c3",
            ),
            chapters.map { it.url },
        )
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
    }

    @Test fun joinsMultiPageContentAndAppliesReplaceRegex() = runBlocking {
        val service = LegadoContentService(fetcher = FakeFetcher(fullPages()))

        val first = service.content(source, "https://novel.example.com/book/1/c1").getOrThrow()
        assertEquals("第一章正文加粗", first)

        val second = service.content(source, "https://novel.example.com/book/1/c2").getOrThrow()
        assertEquals("前半段\n\n后半段", second)
    }

    @Test fun buildsReadableTxtForWholeBook() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val out = File(context.cacheDir, "合成书.txt")
        val service = LegadoContentService(fetcher = FakeFetcher(fullPages()))

        val result = service.fetchBook(
            source = source,
            bookUrl = "https://novel.example.com/book/1",
            bookTitle = "合成书名",
            author = "合成作者",
            outFile = out,
        )

        assertTrue(result is BookFetchResult.Ok)
        assertEquals(3, (result as BookFetchResult.Ok).chapterCount)
        val text = out.readText()
        assertTrue(text.startsWith("合成书名\n作者：合成作者"))
        assertTrue(text.contains("第一章 起点"))
        assertTrue(text.contains("第三章 收束"))
        // 章节顺序必须与目录一致
        assertTrue(text.indexOf("第一章 起点") < text.indexOf("第二章 转折"))
        assertTrue(text.indexOf("第二章 转折") < text.indexOf("第三章 收束"))
        // 广告行被净化、HTML 标签被剥离
        assertFalse(text.contains("广告："))
        assertFalse(text.contains("<div"))
    }

    @Test fun anyChapterFailureAbortsWholeBookAndWritesNothing() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val out = File(context.cacheDir, "半本不该存在.txt")
        // 缺第三章页面 → 整本失败
        val pages = fullPages().toMutableMap().apply { remove("https://novel.example.com/book/1/c3") }
        val service = LegadoContentService(fetcher = FakeFetcher(pages))

        val result = service.fetchBook(source, "https://novel.example.com/book/1", "合成书名", null, out)

        assertTrue(result is BookFetchResult.Failed)
        val message = (result as BookFetchResult.Failed).message
        assertTrue(message, message.contains("第 3/3 章"))
        assertFalse("失败时不得留下半本书文件", out.exists())
    }

    @Test fun emptyTocAndEmptyContentReportExplicitErrors() = runBlocking {
        val detailOnly = mapOf("https://novel.example.com/book/1" to detailPage)
        val noToc = LegadoContentService(fetcher = FakeFetcher(detailOnly))
            .chapters(source, "https://novel.example.com/book/1")
        assertTrue(noToc.isFailure)
        assertTrue(noToc.exceptionOrNull()!!.message!!.contains("目录"))

        val emptyContentPages = fullPages().toMutableMap().apply {
            this["https://novel.example.com/book/1/c1"] = """<html><body><div class="nothing">空</div></body></html>"""
        }
        val emptyContent = LegadoContentService(fetcher = FakeFetcher(emptyContentPages))
            .content(source, "https://novel.example.com/book/1/c1")
        assertTrue(emptyContent.isFailure)
        assertTrue(emptyContent.exceptionOrNull()!!.message!!.contains("正文"))
    }

    @Test fun replaceRegexSupportsReplacementFormAndSkipsJsForm() = runBlocking {
        // `##正则##替换` 形态：替换而不是删除
        val replaced = source.copy(
            ruleContent = source.ruleContent.copy(replaceRegex = "##广告：(.*)##【已屏蔽】"),
        )
        val service = LegadoContentService(fetcher = FakeFetcher(fullPages()))
        val text = service.content(replaced, "https://novel.example.com/book/1/c1").getOrThrow()
        assertEquals("第一章正文加粗【已屏蔽】", text)

        // JS 形态（P4 未实现）：原样保留，不误删正文、不崩
        val jsForm = source.copy(ruleContent = source.ruleContent.copy(replaceRegex = "@js:result.replace(/x/g,'')"))
        val untouched = LegadoContentService(fetcher = FakeFetcher(fullPages()))
            .content(jsForm, "https://novel.example.com/book/1/c1").getOrThrow()
        assertEquals("第一章正文加粗广告：买买买", untouched)
    }

    @Test fun tocPaginationStopsWhenPageRepeats() = runBlocking {
        // nextTocUrl 指回第一页 → 不能在两页之间打转
        val loopPages = mapOf(
            "https://novel.example.com/book/1" to detailPage,
            "https://novel.example.com/book/1/toc" to
                """<html><body><div class="chapter-list"><a href="/book/1/c1">第一章</a></div>
                   <a class="next-page" href="/book/1/toc">下一页</a></body></html>""",
        )
        val chapters = LegadoContentService(fetcher = FakeFetcher(loopPages))
            .chapters(source, "https://novel.example.com/book/1").getOrThrow()
        assertEquals(1, chapters.size)
    }
}
