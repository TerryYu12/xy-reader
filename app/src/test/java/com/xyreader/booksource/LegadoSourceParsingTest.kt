package com.xyreader.booksource

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 书源模型与规则解析（P1）。
 *
 * 全部使用**合成源**（example.com），仓库不引入任何真实站点数据；
 * 字段名取自真实源实测，见 docs/superpowers/plans/2026-10-17-legado-source-engine.md。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LegadoSourceParsingTest {

    // ---------- 规则解析 ----------

    @Test fun recognizesRulePrefixes() {
        assertEquals(LegadoRuleKind.CSS, LegadoRule.parse("@css:div.item > a@text").steps.single().kind)
        assertEquals(LegadoRuleKind.XPATH, LegadoRule.parse("@XPath://div[@id='list']/li").steps.single().kind)
        assertEquals(LegadoRuleKind.XPATH, LegadoRule.parse("//div[@class='x']/a").steps.single().kind)
        assertEquals(LegadoRuleKind.JSON_PATH, LegadoRule.parse("@json:$.data.list[*].name").steps.single().kind)
        assertEquals(LegadoRuleKind.JSON_PATH, LegadoRule.parse("$.books[0].title").steps.single().kind)
        assertEquals(LegadoRuleKind.REGEX, LegadoRule.parse("@regex:作者：(.+)").steps.single().kind)
        // 默认选择器语法（Legado 主力写法，无前缀）
        val default = LegadoRule.parse("class.book-item@tag.a@text").steps.single()
        assertEquals(LegadoRuleKind.DEFAULT_CSS, default.kind)
        assertEquals("class.book-item@tag.a@text", default.expression)
        // 前缀只剥掉标记本身，表达式原样保留
        assertEquals("$.a.b", LegadoRule.parse("@json:$.a.b").steps.single().expression)
    }

    @Test fun splitsFallbackChainAndInterleave() {
        val fallback = LegadoRule.parse("class.a@text||class.b@text||@css:div.c")
        assertEquals(3, fallback.alternatives.size)
        assertFalse(fallback.interleave)
        assertEquals(LegadoRuleKind.CSS, fallback.alternatives[2].single().kind)

        val chain = LegadoRule.parse("class.list@tag.li&&class.item@text")
        assertEquals(1, chain.alternatives.size)
        assertEquals(2, chain.alternatives[0].size)
        assertEquals("class.list@tag.li", chain.alternatives[0][0].expression)
        assertEquals("class.item@text", chain.alternatives[0][1].expression)

        val interleave = LegadoRule.parse("class.a@text%%class.b@text")
        assertTrue(interleave.interleave)
        assertEquals(2, interleave.alternatives.size)
    }

    @Test fun parsesRegexReplaceSegment() {
        val replaced = LegadoRule.parse("class.title@text##最新章节：(.+)##$1")
        val step = replaced.steps.single()
        assertEquals("class.title@text", step.expression)
        assertEquals("最新章节：(.+)", step.replaceRegex)
        assertEquals("${'$'}1", step.replaceWith)

        // 只有两段时表示「替换为空」
        assertEquals("", LegadoRule.parse("class.title@text##\\s+").steps.single().replaceWith)
        // 没有替换段时两者都为 null
        assertNull(LegadoRule.parse("class.title@text").steps.single().replaceRegex)
    }

    @Test fun keepsJsRulesWholeAndFlagsThem() {
        val rule = LegadoRule.parse("@js:java.getString('class.a@text').replace(/a||b/, '')")
        val step = rule.steps.single()
        assertEquals(LegadoRuleKind.JS, step.kind)
        // JS 里的 || 不参与切分（切了脚本就坏了）
        assertEquals(1, rule.alternatives.size)
        assertTrue(rule.usesJs)
        assertTrue(step.expression.contains("a||b"))

        val tagged = LegadoRule.parse("<js>result.replace('##', '')</js>")
        assertEquals(LegadoRuleKind.JS, tagged.steps.single().kind)
        assertTrue(tagged.usesJs)
    }

    @Test fun doesNotSplitInsideQuotesOrTemplate() {
        val quoted = LegadoRule.parse("class.a[data-key='p||q']@text")
        assertEquals(1, quoted.alternatives.size)
        assertEquals("class.a[data-key='p||q']@text", quoted.steps.single().expression)

        val template = LegadoRule.parse("{{baseUrl}}/search?q={{key}}&page=1")
        assertTrue(template.steps.single().usesTemplate)
        assertFalse(template.interleave)
    }

    @Test fun blankRulesAreEmptyNotBroken() {
        for (raw in listOf(null, "", "   ")) {
            val rule = LegadoRule.parse(raw)
            assertTrue(rule.isEmpty)
            assertFalse(rule.usesJs)
            assertTrue(rule.steps.isEmpty())
        }
    }

    // ---------- 书源模型 ----------

    @Test fun parsesArrayAndSingleObject() {
        val array = LegadoBookSource.parseImport("[$SYNTHETIC_A,$SYNTHETIC_B]")
        assertEquals(2, array.size)
        assertEquals("合成源A", array[0].name)
        assertEquals("合成源B", array[1].name)

        val single = LegadoBookSource.parseImport(SYNTHETIC_A)
        assertEquals(1, single.size)
        assertEquals("合成源A", single[0].name)

        assertTrue(LegadoBookSource.parseImport("").isEmpty())
        assertTrue(LegadoBookSource.parseImport("not json at all").isEmpty())
    }

    @Test fun mapsFieldsMeasuredFromRealSources() {
        val source = LegadoBookSource.parseImport(SYNTHETIC_B).single()
        assertEquals("合成源B", source.name)
        assertEquals("https://reader.example.com", source.url)
        assertEquals(BookSourceType.COMIC, source.type)
        assertEquals("测试分组", source.group)
        assertEquals("禁用示例", source.comment)
        assertFalse(source.enabled)
        assertEquals("https://reader.example.com/search?q={{key}}", source.searchUrl)
        assertEquals("json", source.header)
        assertEquals("class.result@tag.li", source.ruleSearch.bookList)
        assertEquals("class.title@text", source.ruleSearch.name)
        assertEquals("class.list@tag.a", source.ruleToc.chapterList)
        assertEquals("class.content@html", source.ruleContent.content)
        assertEquals("class.next@href", source.ruleContent.nextContentUrl)
        assertTrue(source.canSearch)
        assertFalse(source.requiresLogin)
        assertEquals("reader.example.com", source.hostKey)
    }

    @Test fun skipsBrokenEntriesAndDefaultsEnabledToTrue() {
        val mixed = """
            [
              {"bookSourceName":"缺 URL 的源","bookSourceUrl":""},
              $SYNTHETIC_A,
              {"bookSourceName":"显式禁用","bookSourceUrl":"https://disabled.example.com","enabled":false},
              "不是对象"
            ]
        """.trimIndent()
        val parsed = LegadoBookSource.parseImport(mixed)
        assertEquals("坏条目应被跳过，只留两条合法源", 2, parsed.size)
        assertTrue("缺 enabled 字段按启用处理", parsed[0].enabled)
        assertFalse(parsed[1].enabled)
    }

    @Test fun flagsLoginRequirementAndUnknownType() {
        val loginSource = LegadoBookSource.parseImport(
            """
            {"bookSourceName":"需登录","bookSourceUrl":"https://login.example.com",
             "bookSourceType":9,"loginUrl":"https://login.example.com/sign-in",
             "ruleSearch":{"bookList":"class.a"}}
            """.trimIndent(),
        ).single()
        assertTrue(loginSource.requiresLogin)
        assertEquals("未知类型不猜，落到 UNKNOWN", BookSourceType.UNKNOWN, loginSource.type)
        assertFalse("没有 searchUrl 时不可搜索", loginSource.canSearch)
    }

    private companion object {
        const val SYNTHETIC_A = """
            {"bookSourceName":"合成源A","bookSourceUrl":"https://alpha.example.com",
             "bookSourceType":0,"enabled":true,"searchUrl":"https://alpha.example.com/s?q={{key}}",
             "ruleSearch":{"bookList":"class.item","name":"class.name@text"},
             "ruleToc":{"chapterList":"class.chapters@tag.a","chapterName":"text","chapterUrl":"href"},
             "ruleContent":{"content":"class.content@html"}}
        """

        const val SYNTHETIC_B = """
            {"bookSourceName":"合成源B","bookSourceUrl":"https://reader.example.com","bookSourceType":2,
             "bookSourceGroup":"测试分组","bookSourceComment":"禁用示例","enabled":false,"header":"json",
             "searchUrl":"https://reader.example.com/search?q={{key}}",
             "ruleSearch":{"bookList":"class.result@tag.li","name":"class.title@text","author":"class.author@text"},
             "ruleBookInfo":{"name":"class.title@text","intro":"class.intro@text"},
             "ruleToc":{"chapterList":"class.list@tag.a","chapterName":"text","chapterUrl":"href",
                        "nextTocUrl":"class.next-page@href"},
             "ruleContent":{"content":"class.content@html","nextContentUrl":"class.next@href"}}
        """
    }
}
