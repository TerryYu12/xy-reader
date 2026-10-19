package com.xyreader.booksource

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JS 规则沙箱：能执行、能取 java.* 桥、**不能触达任何 Java 类**、死循环会被中止。
 *
 * 纯 JVM 测试（Rhino + jsoup，均不依赖 Android 运行时）。
 */
class LegadoJsRuntimeTest {

    private class StubBridge(
        override val baseUrl: String = "https://novel.example.com",
        override val sourceName: String = "合成书源",
    ) : JsBridge {
        var ajaxCalls = mutableListOf<String>()
        var lastStringRule: String? = null
        var lastElementRule: String? = null
        var elements: List<Element> = emptyList()
        private val vars = HashMap<String, String>()

        override fun getString(rule: String): String {
            lastStringRule = rule
            return "取自页面：$rule"
        }

        override fun getStringList(rule: String): List<String> = listOf("甲", "乙", "丙")

        override fun getElementList(rule: String): List<Element> {
            lastElementRule = rule
            return elements
        }

        override fun get(key: String): String = vars[key].orEmpty()

        override fun put(key: String, value: String) {
            vars[key] = value
        }

        override fun ajax(url: String): String {
            ajaxCalls += url
            return "AJAX:$url"
        }

        override fun log(message: String) = Unit
    }

    private val bridge = StubBridge()
    private val runtime = LegadoJsRuntime(bridge)

    private fun ok(script: String, initial: String = ""): String {
        val result = runtime.eval(script, initial)
        assertTrue("脚本应当成功：$script -> $result", result is JsResult.Ok)
        return (result as JsResult.Ok).value
    }

    @Test fun evaluatesPlainJsWithResultVariable() {
        assertEquals("ABC", ok("result.toUpperCase()", initial = "abc"))
        assertEquals("前缀-abc", ok("'前缀-' + result", initial = "abc"))
        // 标准库可用：JSON / RegExp / 数组
        assertEquals("2", ok("JSON.parse('{\"a\":2}').a"))
        assertEquals("替", ok("'替换'.replace(/换/, '')"))
        assertEquals("a,b", ok("['a','b'].join(',')"))
        // 未调用 java.getElementList 时，数组一律按文本连接（句柄检测不生效）——文本级 JS 行为不变
        assertEquals("1,2,3", ok("[1,2,3]"))
    }

    @Test fun exposesJavaBridgeFunctions() {
        assertEquals("取自页面：class.title@text", ok("java.getString('class.title@text')"))
        assertEquals("class.title@text", bridge.lastStringRule)

        assertEquals("甲|乙|丙", ok("java.getStringList('x').join('|')"))

        assertEquals("值", ok("java.put('k','值'); java.get('k')"))
        assertEquals("", ok("java.get('没有这个键')"))

        assertEquals("AJAX:https://novel.example.com/a", ok("java.ajax(baseUrl + '/a')"))
        assertEquals(listOf("https://novel.example.com/a"), bridge.ajaxCalls)
        assertEquals("合成书源", ok("sourceName"))
    }

    @Test fun getElementListReturnsHandlesResolvedToNodes() {
        bridge.elements = Jsoup.parse("<ul><li>甲</li><li>乙</li><li>丙</li></ul>").select("li").toList()

        val result = runtime.eval("java.getElementList('class.list@tag.li')", "")
        assertTrue("应还原成节点集合：$result", result is JsResult.OkElements)
        assertEquals(listOf("甲", "乙", "丙"), (result as JsResult.OkElements).elements.map { it.text() })
        assertEquals("class.list@tag.li", bridge.lastElementRule)

        // 句柄数组可先用脚本处理（.slice/.filter）再返回，仍能还原
        val sliced = runtime.eval("java.getElementList('x').slice(1)", "")
        assertTrue(sliced is JsResult.OkElements)
        assertEquals(listOf("乙", "丙"), (sliced as JsResult.OkElements).elements.map { it.text() })

        // getElementList 返回空 → 空句柄数组（长度 0），不编造节点
        bridge.elements = emptyList()
        assertEquals("0", ok("java.getElementList('x').length"))
    }

    @Test fun nonHandleArraysAndOutOfRangeHandlesFallBackToText() {
        bridge.elements = Jsoup.parse("<ul><li>甲</li></ul>").select("li").toList()

        // 越界句柄：注册表非空，但 99 不在范围内 → 退回文本而非乱指节点
        val outOfRange = runtime.eval("java.getElementList('x'); [0, 99]", "")
        assertTrue(outOfRange is JsResult.Ok)
        assertEquals("0,99", (outOfRange as JsResult.Ok).value)

        // 非句柄（字符串）混入 → 退回文本
        val mixed = runtime.eval("java.getElementList('x').concat(['字'])", "")
        assertTrue(mixed is JsResult.Ok)

        // 空数组 → 文本 ""
        assertTrue(runtime.eval("java.getElementList('x').slice(9)", "") is JsResult.Ok)
    }

    @Test fun javaClassesAndPackagesAreInvisible() {
        // 包/类访问被 ClassShutter 挡掉：只剩我们注入的桥对象
        assertEquals("undefined", ok("typeof java.lang"))
        assertEquals("undefined", ok("typeof java.io"))
        assertEquals("undefined", ok("typeof Packages"))
        assertEquals("undefined", ok("typeof javax"))
        assertEquals("function", ok("typeof java.getString"))
    }

    @Test fun touchingRealClassesFailsInsteadOfExecuting() {
        val result = runtime.eval("new java.io.File('x')", "")
        assertTrue(result is JsResult.Failed)
    }

    @Test fun scriptErrorsBecomeReadableFailures() {
        val result = runtime.eval("throw new Error('故意报错')", "")
        assertTrue(result is JsResult.Failed)
        assertTrue((result as JsResult.Failed).message.contains("故意报错"))
    }

    @Test fun infiniteLoopIsAbortedByWallClockAndPoisonsTheEngine() {
        val started = System.currentTimeMillis()
        val runtime = LegadoJsRuntime(bridge, timeoutMs = 500)
        // 用「有限但很慢」的循环：真·死循环会让线程永久空转（Rhino 不响应中断），拖累同 JVM 的其他测试
        val result = runtime.eval("var s=0; for(var i=0;i<50000000;i++){ s+=i; } s", "")
        val elapsed = System.currentTimeMillis() - started

        assertTrue("死循环必须被中止", result is JsResult.Failed)
        assertTrue("中止要快（实际 ${elapsed}ms）", elapsed < 10_000)
        assertTrue((result as JsResult.Failed).message.contains("超时"))

        // 被杀不掉的线程会一直空转：引擎必须毒化，避免每个源都泄漏一个线程
        val second = runtime.eval("1+1", "")
        assertTrue(second is JsResult.Failed)
        assertTrue((second as JsResult.Failed).message.contains("停用"))
    }
}
