package com.xyreader.booksource

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 规则求值（P2 子集）。
 *
 * HTML / JSON 全部是**合成夹具**，与任何真实站点无关；只验证求值语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoRuleEvaluatorTest {

    private val evaluator = LegadoRuleEvaluator()

    private val html = """
        <html><body>
          <ul class="book-list">
            <li class="item"><a class="title" href="/book/1">第一本</a><span class="author">作者甲</span></li>
            <li class="item"><a class="title" href="/book/2">第二本</a><span class="author">作者乙</span></li>
            <li class="item vip"><a class="title" href="/book/3">第三本</a><span class="author">作者丙</span></li>
          </ul>
          <div class="next-page"><a href="/list?p=2">下一页</a></div>
        </body></html>
    """.trimIndent()

    // ---------- 默认选择器语法 ----------

    @Test fun selectsByClassAndReadsText() {
        val page = evaluator.parseHtml(html)
        val titles = evaluator.strings(LegadoRule.parse("class.item@tag.a@text"), page)
        assertEquals(listOf("第一本", "第二本", "第三本"), titles)
    }

    @Test fun readsAttributes() {
        val page = evaluator.parseHtml(html)
        val hrefs = evaluator.strings(LegadoRule.parse("class.item@tag.a@href"), page)
        assertEquals(listOf("/book/1", "/book/2", "/book/3"), hrefs)

        val authors = evaluator.strings(LegadoRule.parse("class.item@class.author@text"), page)
        assertEquals(listOf("作者甲", "作者乙", "作者丙"), authors)
    }

    @Test fun supportsIndexInSelector() {
        val page = evaluator.parseHtml(html)
        assertEquals("第二本", evaluator.firstString(LegadoRule.parse("class.item.1@tag.a@text"), page))
        assertEquals("第三本", evaluator.firstString(LegadoRule.parse("class.item.-1@tag.a@text"), page))
        // 越界不报错，只是取不到
        assertEquals(null, evaluator.firstString(LegadoRule.parse("class.item.9@tag.a@text"), page))
    }

    @Test fun supportsCssPrefixWithTrailingOperators() {
        val page = evaluator.parseHtml(html)
        val hrefs = evaluator.strings(LegadoRule.parse("@css:ul.book-list > li.item@tag.a@href"), page)
        assertEquals(listOf("/book/1", "/book/2", "/book/3"), hrefs)

        // 纯 CSS 选择器也能直接返回节点集合
        assertEquals(3, evaluator.elements(LegadoRule.parse("@css:li.item"), page).size)
    }

    @Test fun absoluteUrlAndHtmlExtraction() {
        val page = evaluator.parseHtml(html, baseUri = "https://reader.example.com/list")
        val absolute = evaluator.strings(LegadoRule.parse("class.item.0@tag.a@abs:href"), page)
        assertEquals(listOf("https://reader.example.com/book/1"), absolute)

        val outer = evaluator.strings(LegadoRule.parse("class.next-page@tag.a@html"), page)
        assertTrue(outer.single().contains("/list?p=2"))
    }

    // ---------- 组合：回退 / 串联 / 替换 ----------

    @Test fun fallbackPicksFirstNonEmptyAlternative() {
        val page = evaluator.parseHtml(html)
        val rule = LegadoRule.parse("class.nonexistent@text||class.item.0@tag.a@text")
        assertEquals("第一本", evaluator.firstString(rule, page))
    }

    @Test fun allAlternativesEmptyYieldsNothing() {
        val page = evaluator.parseHtml(html)
        val rule = LegadoRule.parse("class.none@text||class.also-none@text")
        assertEquals(null, evaluator.firstString(rule, page))
    }

    @Test fun chainContinuesFromPreviousStep() {
        val page = evaluator.parseHtml(html)
        // 先定位 li，再在 li 内取 a 的文本
        val rule = LegadoRule.parse("class.item&&tag.a@text")
        assertEquals(listOf("第一本", "第二本", "第三本"), evaluator.strings(rule, page))
    }

    @Test fun regexReplaceSegmentIsApplied() {
        val page = evaluator.parseHtml(html)
        val rule = LegadoRule.parse("class.item.0@class.title@text##^第(.+)本${'$'}##${'$'}1号")
        assertEquals("一号", evaluator.firstString(rule, page))

        // 两段式 = 删除匹配内容
        val stripped = LegadoRule.parse("class.item.0@class.title@text##^第")
        assertEquals("一本", evaluator.firstString(stripped, page))
    }

    // ---------- JSONPath 子集 ----------

    @Test fun jsonPathReadsKeysIndexAndWildcard() {
        val payload = """
            {"data":{"list":[{"name":"甲","id":1},{"name":"乙","id":2}],"total":2}}
        """.trimIndent()
        val json = evaluator.parseJson(payload)
        assertEquals("甲", evaluator.firstString(LegadoRule.parse("@json:$.data.list[0].name"), json))
        assertEquals(
            listOf("甲", "乙"),
            evaluator.strings(LegadoRule.parse("@json:$.data.list[*].name"), json),
        )
        assertEquals("2", evaluator.firstString(LegadoRule.parse("$.data.total"), json))
        assertEquals(listOf("1", "2"), evaluator.strings(LegadoRule.parse("$['data']['list'][*]['id']"), json))
    }

    @Test fun jsonPathOnNonJsonYieldsNothing() {
        val page = evaluator.parseHtml(html)
        assertEquals(null, evaluator.firstString(LegadoRule.parse("$.data.list[0].name"), page))
    }

    // ---------- 正则 ----------

    @Test fun regexExtractsFirstCaptureGroup() {
        val page = evaluator.parseHtml(html)
        val rule = LegadoRule.parse("class.item@class.author@text@regex:作者(.+)")
        assertEquals(listOf("甲", "乙", "丙"), evaluator.strings(rule, page))
    }

    // ---------- 模板展开 ----------

    @Test fun expandsTemplateVariables() {
        val withVars = LegadoRuleEvaluator(mapOf("baseUrl" to "https://reader.example.com", "key" to "合成关键词"))
        assertEquals(
            "https://reader.example.com/search?q=合成关键词",
            withVars.expand("{{baseUrl}}/search?q={{key}}"),
        )
    }

    @Test fun unknownTemplateVariableIsLeftVisible() {
        // 不静默吞掉未知变量，便于排障
        assertEquals("{{unknown}}/x", LegadoRuleEvaluator().expand("{{unknown}}/x"))
    }

    // ---------- 未实现的类型必须明确为空，不假装成功 ----------

    // ---------- XPath（P3） ----------

    @Test fun xpathSelectsNodesAttributesAndTextNodes() {
        val page = evaluator.parseHtml(html)
        // 谓词里精确匹配 class（第三个 li 是 class="item vip"，不命中）
        assertEquals(
            listOf("/book/1", "/book/2"),
            evaluator.strings(LegadoRule.parse("//li[@class='item']//a@href"), page),
        )
        // contains 才是「类名包含」语义
        assertEquals(
            listOf("/book/1", "/book/2", "/book/3"),
            evaluator.strings(LegadoRule.parse("//li[contains(@class,'item')]//a@href"), page),
        )
        // 直接取文本节点
        assertEquals(
            listOf("作者甲", "作者乙", "作者丙"),
            evaluator.strings(LegadoRule.parse("//span[@class='author']/text()"), page),
        )
        // 带下标的路径 + 属性节点
        assertEquals("/book/1", evaluator.firstString(LegadoRule.parse("//ul[@class='book-list']/li[1]/a/@href"), page))
    }

    @Test fun invalidXPathYieldsNothingInsteadOfCrashing() {
        val page = evaluator.parseHtml(html)
        assertEquals(null, evaluator.firstString(LegadoRule.parse("//li[unclosed"), page))
        assertEquals(null, evaluator.firstString(LegadoRule.parse("@XPath://*["), page))
    }

    @Test fun xmlSerializationHandlesVoidElements() {
        // <br> 在 HTML 里不必闭合；转 XML 后必须闭合，否则整条 XPath 都会失败
        val page = evaluator.parseHtml("<div class=\"x\">第一行<br>第二行<a href=\"/p/1\">链接</a></div>")
        assertEquals(listOf("/p/1"), evaluator.strings(LegadoRule.parse("//div[@class='x']//a@href"), page))
    }

    // ---------- 未实现：JS（P4） ----------

    @Test fun jsRulesRemainUnimplemented() {
        val page = evaluator.parseHtml(html)
        assertEquals(null, evaluator.firstString(LegadoRule.parse("@js:java.getString('class.item@text')"), page))
        assertTrue(LegadoRule.parse("@js:x").usesJs)
    }
}
