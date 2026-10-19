package com.xyreader.booksource

/** 一条规则串的求值方式（前缀决定） */
enum class LegadoRuleKind {
    /** 默认选择器语法（`class.x@tag.a@text` 这类，Legado 的主力写法） */
    DEFAULT_CSS,

    /** 显式 `@css:` */
    CSS,

    /** `@XPath:` 或 `//` 开头 */
    XPATH,

    /** `@json:` 或 `$.` 开头 */
    JSON_PATH,

    /** `@regex:` */
    REGEX,

    /** `@js:` 或含 `<js>` */
    JS,
}

/**
 * 串联链上的一步（`&&` 分隔）。
 *
 * [replaceRegex] / [replaceWith] 来自 `##` 替换段：`表达式##正则##替换`。
 * [usesTemplate] 表示表达式里含 `{{ }}` 插值（URL 与变量）。
 */
data class LegadoRuleStep(
    val kind: LegadoRuleKind,
    val expression: String,
    val replaceRegex: String? = null,
    val replaceWith: String? = null,
    val usesTemplate: Boolean = false,
)

/**
 * 解析后的规则。
 *
 * [alternatives] 是回退链：外层是 `||`（前一个取不到就用下一个），
 * 内层是 `&&`（对上一段结果继续取值）。[interleave] 标记 `%%`（结果交错拼接，
 * 实测 46898 条规则里只有 10 条用，P1 仅标记，求值期再区分）。
 *
 * JS 规则（`@js:` / `<js>`）**不切分操作符**：JS 里出现 `||`、`&&`、`##` 是常态，
 * 按字面切分会把脚本切坏（实测 6% 的规则是 JS）。
 */
data class LegadoRule(
    val raw: String,
    val alternatives: List<List<LegadoRuleStep>>,
    val interleave: Boolean = false,
) {
    val isEmpty: Boolean
        get() = alternatives.isEmpty() || alternatives.all { chain -> chain.isEmpty() }

    /** 是否含 JS 规则：本阶段不执行，导入时要向用户说明该规则暂不支持 */
    val usesJs: Boolean
        get() = alternatives.any { chain -> chain.any { it.kind == LegadoRuleKind.JS } }

    /** 该规则涉及的全部步骤（便于统计与诊断） */
    val steps: List<LegadoRuleStep> get() = alternatives.flatten()

    companion object {
        val EMPTY = LegadoRule("", emptyList())

        fun parse(raw: String?): LegadoRule {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return EMPTY

            // JS 整体处理，绝不切分
            if (text.startsWith(JS_PREFIX, ignoreCase = true) || text.contains(JS_TAG_OPEN, ignoreCase = true)) {
                val expression = if (text.startsWith(JS_PREFIX, ignoreCase = true)) {
                    text.substring(JS_PREFIX.length).trim()
                } else {
                    text
                }
                return LegadoRule(text, listOf(listOf(LegadoRuleStep(LegadoRuleKind.JS, expression))))
            }

            val interleave = splitTopLevel(text, INTERLEAVE).size > 1
            val alternatives = splitTopLevel(text, INTERLEAVE)
                .flatMap { segment -> splitTopLevel(segment, FALLBACK) }
                .map { alternative ->
                    splitTopLevel(alternative, CHAIN).mapNotNull { step -> parseStep(step) }
                }
                .filter { it.isNotEmpty() }

            return LegadoRule(text, alternatives, interleave)
        }

        private const val JS_PREFIX = "@js:"
        private const val JS_TAG_OPEN = "<js>"
        private const val INTERLEAVE = "%%"
        private const val FALLBACK = "||"
        private const val CHAIN = "&&"
        private const val REPLACE = "##"

        /** 解析单个步骤：切 `##` 替换段 → 判前缀 → 标记模板插值 */
        private fun parseStep(step: String): LegadoRuleStep? {
            val parts = splitTopLevel(step, REPLACE)
            val head = parts.firstOrNull()?.trim().orEmpty()
            if (head.isEmpty()) return null
            val replaceRegex = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
            // `表达式##正则`（两段）在 Legado 里表示「删除匹配内容」→ 替换为空串；
            // 完全没有 ## 段时才是 null（不替换）
            val replaceWith = if (parts.size >= 2) parts.getOrNull(2).orEmpty() else null
            return LegadoRuleStep(
                kind = kindOf(head),
                expression = stripPrefix(head),
                replaceRegex = replaceRegex,
                replaceWith = replaceWith,
                usesTemplate = head.contains("{{"),
            )
        }

        private fun kindOf(text: String): LegadoRuleKind = when {
            text.startsWith("@css:", ignoreCase = true) -> LegadoRuleKind.CSS
            text.startsWith("@xpath:", ignoreCase = true) -> LegadoRuleKind.XPATH
            text.startsWith("@json:", ignoreCase = true) -> LegadoRuleKind.JSON_PATH
            text.startsWith("@regex:", ignoreCase = true) -> LegadoRuleKind.REGEX
            text.startsWith("//") -> LegadoRuleKind.XPATH
            // `$.a.b` 与 `$['a']...` 都算 JSONPath
            text.startsWith("$") -> LegadoRuleKind.JSON_PATH
            else -> LegadoRuleKind.DEFAULT_CSS
        }

        private fun stripPrefix(text: String): String = when {
            text.startsWith("@css:", ignoreCase = true) -> text.substring(5).trim()
            text.startsWith("@xpath:", ignoreCase = true) -> text.substring(7).trim()
            text.startsWith("@json:", ignoreCase = true) -> text.substring(6).trim()
            text.startsWith("@regex:", ignoreCase = true) -> text.substring(7).trim()
            else -> text
        }

        /**
         * 顶层切分：跳过 `{{ }}` 插值、单/双引号字符串、方括号下标（`[@class='x']`）与反斜杠转义里的分隔符。
         *
         * 典型场景：`@js:` 之外的正则、模板、XPath 谓词里出现 `||`、`&&`、`@`，按字面切会切坏规则。
         * 供规则解析与求值器共用（求值器用它切 `@` 串联）。
         */
        internal fun splitTopLevel(text: String, delimiter: String): List<String> {
            val out = ArrayList<String>()
            val builder = StringBuilder()
            var i = 0
            var inSingle = false
            var inDouble = false
            var templateDepth = 0
            var bracketDepth = 0
            while (i < text.length) {
                val ch = text[i]
                when {
                    ch == '\\' && i + 1 < text.length -> {
                        builder.append(ch).append(text[i + 1])
                        i += 2
                        continue
                    }
                    ch == '\'' && !inDouble && templateDepth == 0 -> inSingle = !inSingle
                    ch == '"' && !inSingle && templateDepth == 0 -> inDouble = !inDouble
                    ch == '[' && !inSingle && !inDouble && templateDepth == 0 -> bracketDepth++
                    ch == ']' && !inSingle && !inDouble && templateDepth == 0 && bracketDepth > 0 -> bracketDepth--
                    ch == '{' && i + 1 < text.length && text[i + 1] == '{' && !inSingle && !inDouble -> {
                        templateDepth++
                        builder.append("{{")
                        i += 2
                        continue
                    }
                    ch == '}' && i + 1 < text.length && text[i + 1] == '}' && templateDepth > 0 -> {
                        templateDepth--
                        builder.append("}}")
                        i += 2
                        continue
                    }
                    !inSingle && !inDouble && templateDepth == 0 && bracketDepth == 0 &&
                        text.startsWith(delimiter, i) -> {
                        out += builder.toString()
                        builder.setLength(0)
                        i += delimiter.length
                        continue
                    }
                }
                builder.append(ch)
                i++
            }
            out += builder.toString()
            return out
        }
    }
}
