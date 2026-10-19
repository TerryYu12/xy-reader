package com.xyreader.booksource

import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource

/** 求值结果：要么是一批 HTML 节点，要么是一批文本；取不到就是 [Empty] */
sealed interface RuleValue {
    data class HtmlNodes(val elements: List<Element>) : RuleValue
    data class Texts(val values: List<String>) : RuleValue
    data object Empty : RuleValue
}

/**
 * Legado 规则求值（P2 子集）。
 *
 * 已实现：`class.` / `tag.` / `id.` / 取文本 / 取属性 / `@css:` / `@json:`(JSONPath 子集) /
 * `@regex:` / `@` 串联 / `&&` 串联 / `||` 回退 / `##` 替换 / `{{ }}` 模板展开 /
 * `@XPath:`（P3）/ `@js:`（P4，文本级 + `java.getElementList` 节点级）。
 *
 * **未实现**（返回 [RuleValue.Empty]，由上层提示「该规则暂不支持」）：
 * 默认语法里的排除项 `!`、索引区间 `N:M`、`children`/`parent` 等结构操作。
 *
 * 取不到时返回空而不是抛异常：一条源写错不该让整次搜索崩掉。
 */
class LegadoRuleEvaluator(
    private val templateVars: Map<String, String> = emptyMap(),
    /** JS 规则执行器；为 null 时 JS 步骤返回空（P4 之前的行为） */
    private val js: JsEvaluator? = null,
) {

    /** 挂上 JS 执行器（页面解析出根节点后再挂，这样 java.getString 才有页面上下文） */
    fun withJs(js: JsEvaluator): LegadoRuleEvaluator = LegadoRuleEvaluator(templateVars, js)


    /** 解析 HTML；后续规则在这个文档上求值 */
    fun parseHtml(html: String, baseUri: String = ""): RuleValue =
        RuleValue.HtmlNodes(listOf(Jsoup.parse(html, baseUri)))

    /** 把 JSON 文本当求值输入（JSONPath 步骤再往里取值） */
    fun parseJson(json: String): RuleValue = RuleValue.Texts(listOf(json))

    /** 模板展开：`{{name}}` → 变量值；未知变量原样保留（便于排障，也不静默吞掉） */
    fun expand(text: String): String {
        if (!text.contains("{{")) return text
        val regex = Regex("\\{\\{\\s*([A-Za-z0-9_.\\-]+)\\s*\\}\\}")
        return regex.replace(text) { match ->
            templateVars[match.groupValues[1]] ?: match.value
        }
    }

    /** 完整求值：按回退链依次尝试，第一个非空的替代项胜出 */
    fun evaluate(rule: LegadoRule, input: RuleValue): RuleValue {
        for (alternative in rule.alternatives) {
            var current: RuleValue = input
            for (step in alternative) {
                current = evaluateStep(step, current)
                if (current is RuleValue.Empty) break
            }
            if (current !is RuleValue.Empty) return current
        }
        return RuleValue.Empty
    }

    /** 便捷取值：第一个非空文本 */
    fun firstString(rule: LegadoRule, input: RuleValue): String? =
        (evaluate(rule, input) as? RuleValue.Texts)
            ?.values
            ?.firstOrNull { it.isNotBlank() }

    /** 便捷取值：全部文本（已去空、已去重顺序保留） */
    fun strings(rule: LegadoRule, input: RuleValue): List<String> =
        (evaluate(rule, input) as? RuleValue.Texts)?.values?.filter { it.isNotBlank() }.orEmpty()

    /** 便捷取值：全部元素 */
    fun elements(rule: LegadoRule, input: RuleValue): List<Element> =
        (evaluate(rule, input) as? RuleValue.HtmlNodes)?.elements.orEmpty()

    private fun evaluateStep(step: LegadoRuleStep, input: RuleValue): RuleValue {
        val expression = expand(step.expression)
        val raw = when (step.kind) {
            LegadoRuleKind.JS -> return runJs(step.expression, input)
            LegadoRuleKind.XPATH -> selectXPath(expression, input)
            LegadoRuleKind.CSS -> selectCss(expression, input)
            LegadoRuleKind.DEFAULT_CSS -> selectDefault(expression, input)
            LegadoRuleKind.JSON_PATH -> selectJsonPath(expression, input)
            LegadoRuleKind.REGEX -> selectRegex(expression, input)
        }
        return applyReplace(step, raw)
    }

    // ---------- HTML ----------

    /**
     * `@css:选择器` —— 选择器后面仍可继续用 `@` 串接默认语法的取值操作
     * （例如 `@css:.item@tag.a@href`：先按 CSS 选，再取 a 标签的 href）。
     */
    private fun selectCss(expression: String, input: RuleValue): RuleValue {
        val segments = splitDefaultSegments(expression)
        val selector = segments.firstOrNull() ?: return RuleValue.Empty
        val nodes = input as? RuleValue.HtmlNodes ?: return RuleValue.Empty
        val selected = nodes.elements.flatMap { element -> element.select(selector) }
        if (selected.isEmpty()) return RuleValue.Empty
        var current: RuleValue = RuleValue.HtmlNodes(selected)
        for (segment in segments.drop(1)) {
            val htmlNodes = current as? RuleValue.HtmlNodes ?: return RuleValue.Empty
            current = applyHtmlSegment(segment, htmlNodes)
            if (current is RuleValue.Empty) return RuleValue.Empty
        }
        return current
    }

    /**
     * 默认选择器语法（Legado 主力写法）：用 `@` 把若干操作串起来。
     *
     * `class.name` / `tag.a` / `id.main` 选节点；`text` / `ownText` / `textNodes` 取文本；
     * `html` 取整段 HTML；其它裸词当作属性名（`href`、`src`、`title`…）。
     * 选择器尾部可带索引：`class.item.0` 取第一个、`class.item.-1` 取最后一个。
     */
    private fun selectDefault(expression: String, input: RuleValue): RuleValue {
        var current: RuleValue = input
        for (segment in splitDefaultSegments(expression)) {
            current = when {
                // 链中途切换求值方式：`class.author@text@regex:作者(.+)` 这类写法
                segment.startsWith("regex:", ignoreCase = true) -> selectRegex(segment.substringAfter(':'), current)
                segment.startsWith("json:", ignoreCase = true) -> selectJsonPath(segment.substringAfter(':'), current)
                segment.startsWith("css:", ignoreCase = true) -> selectCss(segment.substringAfter(':'), current)
                // P3 / P4 未实现
                segment.startsWith("xpath:", ignoreCase = true) -> return RuleValue.Empty
                segment.startsWith("js:", ignoreCase = true) -> runJs(segment.substringAfter(':'), current)
                else -> {
                    val htmlNodes = current as? RuleValue.HtmlNodes ?: return RuleValue.Empty
                    applyHtmlSegment(segment, htmlNodes)
                }
            }
            if (current is RuleValue.Empty) return RuleValue.Empty
        }
        return current
    }

    /** `@` 串联切分：方括号（`[@class='x']`）、引号、`{{ }}` 内的 `@` 不参与切分 */
    private fun splitDefaultSegments(expression: String): List<String> =
        LegadoRule.splitTopLevel(expression, "@").map { it.trim() }.filter { it.isNotEmpty() }

    private fun applyHtmlSegment(segment: String, nodes: RuleValue.HtmlNodes): RuleValue {
        val (body, index) = splitIndex(segment)
        return when {
            body.startsWith("class.") -> pick(nodes.elements.flatMap { it.select("." + body.removePrefix("class.")) }, index)
            body.startsWith("tag.") -> pick(nodes.elements.flatMap { it.select(body.removePrefix("tag.")) }, index)
            body.startsWith("id.") -> pick(nodes.elements.flatMap { it.select("#" + body.removePrefix("id.")) }, index)
            body == "html" -> RuleValue.Texts(listOf(nodes.elements.joinToString("\n") { it.outerHtml() }))
            body == "text" || body == "textNodes" || body == "ownText" -> RuleValue.Texts(
                nodes.elements.map { it.text().trim() }.filter { it.isNotEmpty() },
            )
            body.startsWith("abs:") -> RuleValue.Texts(
                nodes.elements.mapNotNull { it.absUrl(body.removePrefix("abs:")).takeIf { url -> url.isNotBlank() } },
            )
            else -> RuleValue.Texts(
                nodes.elements.mapNotNull { element ->
                    element.attr(body).trim().takeIf { it.isNotEmpty() }
                },
            )
        }
    }

    /** 选择器尾部的 `.N` / `.-N` 索引 */
    private fun splitIndex(segment: String): Pair<String, Int?> {
        val match = INDEX_SUFFIX.find(segment) ?: return segment to null
        return segment.substring(0, match.range.first) to match.groupValues[1].toIntOrNull()
    }

    private fun pick(elements: List<Element>, index: Int?): RuleValue {
        if (elements.isEmpty()) return RuleValue.Empty
        if (index == null) return RuleValue.HtmlNodes(elements)
        val resolved = if (index < 0) elements.size + index else index
        val element = elements.getOrNull(resolved) ?: return RuleValue.Empty
        return RuleValue.HtmlNodes(listOf(element))
    }

    // ---------- JSONPath（子集） ----------

    /**
     * 支持的写法：`$.a.b`、`$.list[0].name`、`$.list[*].name`、`$['a'].b`；
     * 通配 `[*]` 会把匹配到的每一项都收进结果。取值时字符串原样返回，
     * 对象/数组返回其 JSON 文本。
     */
    private fun selectJsonPath(expression: String, input: RuleValue): RuleValue {
        val source = (input as? RuleValue.Texts)?.values?.firstOrNull() ?: return RuleValue.Empty
        val path = expression.trim().removePrefix("$").removePrefix(".")
        if (path.isEmpty()) return RuleValue.Empty
        var current: List<Any?> = listOf(parseJsonValue(source) ?: return RuleValue.Empty)
        for (segment in tokenizePath(path)) {
            val next = ArrayList<Any?>()
            for (node in current) {
                when (segment) {
                    is PathSegment.Key -> {
                        val obj = node as? JSONObject ?: continue
                        if (obj.has(segment.name)) next += obj.opt(segment.name)
                    }
                    is PathSegment.Index -> {
                        val array = node as? JSONArray ?: continue
                        array.opt(segment.index)?.let { next += it }
                    }
                    PathSegment.Wildcard -> when (node) {
                        is JSONArray -> for (i in 0 until node.length()) next += node.opt(i)
                        is JSONObject -> node.keys().forEach { key -> next += node.opt(key) }
                        else -> Unit
                    }
                }
            }
            if (next.isEmpty()) return RuleValue.Empty
            current = next
        }
        val texts = current.mapNotNull { value -> value?.let { stringify(it) } }.filter { it.isNotBlank() }
        return if (texts.isEmpty()) RuleValue.Empty else RuleValue.Texts(texts)
    }

    private fun parseJsonValue(text: String): Any? {
        val trimmed = text.trim()
        return runCatching {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> null
            }
        }.getOrNull()
    }

    private fun stringify(value: Any): String = when (value) {
        is String -> value
        JSONObject.NULL -> ""
        else -> value.toString()
    }

    private sealed interface PathSegment {
        data class Key(val name: String) : PathSegment
        data class Index(val index: Int) : PathSegment
        data object Wildcard : PathSegment
    }

    private fun tokenizePath(path: String): List<PathSegment> {
        val segments = ArrayList<PathSegment>()
        var index = 0
        while (index < path.length) {
            when (val ch = path[index]) {
                '.' -> index++
                '[' -> {
                    val end = path.indexOf(']', index)
                    if (end < 0) return segments
                    val inner = path.substring(index + 1, end).trim().trim('\'', '"')
                    when {
                        inner == "*" -> segments += PathSegment.Wildcard
                        inner.toIntOrNull() != null -> segments += PathSegment.Index(inner.toInt())
                        inner.isNotEmpty() -> segments += PathSegment.Key(inner)
                    }
                    index = end + 1
                }
                else -> {
                    val end = path.indexOfFirstFrom(index) { it == '.' || it == '[' }
                    val name = path.substring(index, if (end < 0) path.length else end)
                    if (name.isNotEmpty()) segments += PathSegment.Key(name)
                    index = if (end < 0) path.length else end
                }
            }
        }
        return segments
    }

    private fun String.indexOfFirstFrom(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return -1
    }

    // ---------- 正则 ----------

    /** `@regex:`：每段输入取第一个捕获组（没有捕获组就取整体匹配） */
    private fun selectRegex(expression: String, input: RuleValue): RuleValue {
        val source = (input as? RuleValue.Texts)?.values ?: return RuleValue.Empty
        val regex = runCatching { Regex(expression) }.getOrNull() ?: return RuleValue.Empty
        val found = source.mapNotNull { text ->
            regex.find(text)?.let { match ->
                match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: match.value
            }
        }.filter { it.isNotBlank() }
        return if (found.isEmpty()) RuleValue.Empty else RuleValue.Texts(found)
    }

    // ---------- JS 规则（P4 沙箱） ----------

    /**
     * 执行 JS 规则。`result` 传入当前步骤的值（文本或节点 HTML）。
     *
     * 返回值有两种落法：
     * - 脚本返回「节点句柄数组」（`java.getElementList` 产生的）→ [RuleValue.HtmlNodes]，
     *   于是 `bookList`/`chapterList` 也能用 `@js:java.getElementList('class.result@tag.li')`；
     * - 其余返回值 → 当文本（[RuleValue.Texts]），行为与 P4 一致。
     *
     * 失败（脚本报错/越权/超指令数）一律返回空：一条规则写错不该让整次搜索崩掉，
     * 真实原因由脚本侧 `java.log` 与上层诊断承担。
     */
    private fun runJs(expression: String, input: RuleValue): RuleValue {
        val evaluator = js ?: return RuleValue.Empty
        val initial = when (input) {
            is RuleValue.Texts -> input.values.joinToString("\n")
            is RuleValue.HtmlNodes -> input.elements.joinToString("\n") { it.outerHtml() }
            RuleValue.Empty -> ""
        }
        return when (val outcome = evaluator.eval(expression, initial)) {
            is JsResult.OkElements -> outcome.elements
                .takeIf { it.isNotEmpty() }
                ?.let { RuleValue.HtmlNodes(it) }
                ?: RuleValue.Empty
            is JsResult.Ok -> outcome.value.takeIf { it.isNotBlank() }
                ?.let { RuleValue.Texts(listOf(it)) }
                ?: RuleValue.Empty
            is JsResult.Failed -> RuleValue.Empty
        }
    }

    // ---------- XPath（javax.xml.xpath，不引新依赖） ----------

    /**
     * `//…` 或 `@XPath:`：把当前节点序列化成 XML，用平台自带的 XPath 求值。
     *
     * 后面仍可串接默认语法的取值操作（`//li[@class='item']//a@href`）。
     * 已做 XXE 防护（禁 DTD / 外部实体）；表达式非法或求值失败一律返回空，不抛。
     * 已知代价：HTML 经 XML 序列化后再解析，个别畸形标记可能失真——XPath 只占实测规则的 0.8%，可接受。
     */
    private fun selectXPath(expression: String, input: RuleValue): RuleValue {
        val nodes = input as? RuleValue.HtmlNodes ?: return RuleValue.Empty
        val document = parseXml(serializeAsXml(nodes.elements)) ?: return RuleValue.Empty
        for ((xpathExpression, trailing) in xpathCandidates(expression)) {
            var current = evaluateXPath(xpathExpression, document)
            if (current is RuleValue.Empty) continue
            var usable = true
            for (segment in trailing) {
                val htmlNodes = current as? RuleValue.HtmlNodes
                if (htmlNodes == null) {
                    usable = false
                    break
                }
                current = applyHtmlSegment(segment, htmlNodes)
                if (current is RuleValue.Empty) {
                    usable = false
                    break
                }
            }
            if (usable) return current
        }
        return RuleValue.Empty
    }

    /**
     * XPath 候选写法。
     *
     * XPath 里的属性步（`/a/@href`）和本引擎的 `@` 串联标记同形，无法静态区分，
     * 所以**先整条当 XPath 编译求值**（`//ul/li/a/@href` 这种只有整条才合法），
     * 失败再剥离尾部 `@` 段当串联操作（`//li//a@href` 这种是串联写法）。
     */
    private fun xpathCandidates(expression: String): List<Pair<String, List<String>>> {
        val candidates = ArrayList<Pair<String, List<String>>>()
        candidates += expression.trim() to emptyList()
        val segments = splitDefaultSegments(expression)
        if (segments.size > 1) {
            candidates += segments.first() to segments.drop(1)
        }
        return candidates
    }

    /** 编译并求值一条 XPath；编译/求值失败返回 null，无匹配返回 Empty */
    private fun evaluateXPath(xpathExpression: String, document: org.w3c.dom.Document): RuleValue {
        val compiled = runCatching { XPathFactory.newInstance().newXPath().compile(xpathExpression) }.getOrNull()
            ?: return RuleValue.Empty
        val nodeList = runCatching {
            compiled.evaluate(document, XPathConstants.NODESET) as? NodeList
        }.getOrNull() ?: return RuleValue.Empty

        val elements = ArrayList<Element>()
        val texts = ArrayList<String>()
        for (index in 0 until nodeList.length) {
            val node = nodeList.item(index) ?: continue
            when (node.nodeType) {
                Node.ELEMENT_NODE -> {
                    val xml = serializeNode(node)
                    val element = xml?.let { Jsoup.parseBodyFragment(it).body().children().firstOrNull() }
                    if (element != null) elements.add(element)
                }
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE, Node.ATTRIBUTE_NODE -> {
                    val text = node.nodeValue?.trim()
                    if (!text.isNullOrEmpty()) texts.add(text)
                }
                else -> Unit
            }
        }
        return when {
            elements.isNotEmpty() -> RuleValue.HtmlNodes(elements)
            texts.isNotEmpty() -> RuleValue.Texts(texts)
            else -> RuleValue.Empty
        }
    }

    /** 用 XML 语法序列化（HTML 的 `<br>` 这类自闭合写法在 XML 里必须闭合，否则解析失败） */
    private fun serializeAsXml(elements: List<Element>): String {
        val builder = StringBuilder()
        elements.forEach { element ->
            val owner = element.ownerDocument()
            val previous = owner?.outputSettings()
            try {
                owner?.outputSettings(Document.OutputSettings().syntax(Document.OutputSettings.Syntax.xml))
                builder.append(element.outerHtml()).append('\n')
            } finally {
                if (previous != null) owner.outputSettings(previous)
            }
        }
        return builder.toString()
    }

    private fun serializeNode(node: Node): String? = runCatching {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        val writer = StringWriter()
        transformer.transform(DOMSource(node), StreamResult(writer))
        writer.toString().takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseXml(xml: String): org.w3c.dom.Document? = runCatching {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        // XXE 防护：不解析 DTD、不取外部实体
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false
        factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }.getOrNull()

    // ---------- `##` 替换 ----------

    private fun applyReplace(step: LegadoRuleStep, value: RuleValue): RuleValue {
        val pattern = step.replaceRegex ?: return value
        if (value !is RuleValue.Texts) return value
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return value
        val replacement = step.replaceWith.orEmpty()
        return RuleValue.Texts(value.values.map { regex.replace(it, replacement).trim() })
    }

    private companion object {
        /** 选择器尾部索引：`.0`、`.-1`（取自 Legado 的索引写法） */
        val INDEX_SUFFIX = Regex("\\.(-?\\d+)$")
    }
}
