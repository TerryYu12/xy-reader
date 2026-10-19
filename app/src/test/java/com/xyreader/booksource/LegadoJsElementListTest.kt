package com.xyreader.booksource

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@js:` 规则返回**节点集合**（`java.getElementList`）的求值级验证。
 *
 * 合成夹具、不联网：页面用 jsoup 解析，JS 走真实 Rhino 沙箱 + [PageJsBridge]。
 * 覆盖：句柄数组 → [RuleValue.HtmlNodes]；脚本层可 `.slice` 再返回；文本级 JS 行为不变；无匹配不崩。
 */
class LegadoJsElementListTest {

    private val html = """
        <html><body>
          <ul class="result">
            <li class="row"><a class="title" href="/book/1">合成书一</a><span class="author">甲</span></li>
            <li class="row"><a class="title" href="/book/2">合成书二</a><span class="author">乙</span></li>
          </ul>
        </body></html>
    """.trimIndent()

    /** 组装「页面根 + 无 JS 求值器 + 挂上真实 JS 沙箱/桥」的求值器 */
    private fun evaluator(): Pair<LegadoRuleEvaluator, RuleValue> {
        val base = LegadoRuleEvaluator()
        val root = base.parseHtml(html, baseUri = "https://alpha.example.com/list")
        val withJs = base.withJs(
            LegadoJsRuntime(
                PageJsBridge(
                    root = root,
                    base = base,
                    baseUrl = "https://alpha.example.com/list",
                    sourceName = "合成源",
                    fetch = { "" },
                ),
            ),
        )
        return withJs to root
    }

    @Test fun jsElementListRuleYieldsNodes() {
        val (evaluator, root) = evaluator()
        val rule = LegadoRule.parse("@js:java.getElementList('class.result@tag.li')")
        assertTrue(rule.usesJs)

        val nodes = evaluator.elements(rule, root)
        assertEquals(2, nodes.size)

        // 拿到节点后，后续规则在「单节点作用域」内照常求值（与搜索/目录链路一致）
        val scoped = RuleValue.HtmlNodes(listOf(nodes.first()))
        assertEquals("合成书一", evaluator.firstString(LegadoRule.parse("class.title@text"), scoped))
        assertEquals("/book/1", evaluator.firstString(LegadoRule.parse("class.title@href"), scoped))
    }

    @Test fun scriptCanTransformHandlesBeforeReturning() {
        val (evaluator, root) = evaluator()
        // 脚本层 .slice(1) 只保留第二个节点
        val nodes = evaluator.elements(
            LegadoRule.parse("@js:java.getElementList('class.result@tag.li').slice(1)"),
            root,
        )
        assertEquals(1, nodes.size)
        assertEquals("合成书二", nodes.first().select("a.title").text())
    }

    @Test fun textJsRulesStillYieldText() {
        val (evaluator, root) = evaluator()
        assertEquals(
            "合成书一",
            evaluator.firstString(LegadoRule.parse("@js:java.getString('class.title@text')"), root),
        )
        // 返回非句柄值（数字）→ 仍按文本落，不误判成节点
        assertEquals(listOf("2"), evaluator.strings(LegadoRule.parse("@js:[1,2].length"), root))
    }

    @Test fun jsElementListWithNoMatchesYieldsNothing() {
        val (evaluator, root) = evaluator()
        assertTrue(evaluator.elements(LegadoRule.parse("@js:java.getElementList('class.does-not-exist')"), root).isEmpty())
    }
}
