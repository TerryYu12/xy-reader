package com.xyreader.feedback

/**
 * 日志脱敏（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 本地日志写盘前、上传前（对整份日志）各跑一遍，中转 Worker（infra/log-relay/worker.js）入库前再跑一遍，
 * 共三道保险。**规则的原件在这里**：Worker 里的 `redact` 是逐条移植，正则文本两边完全一致；
 * 改规则时必须两边同步，并同步更新两边的测试向量（见 LogRedactorTest 与 infra/log-relay/test）。
 *
 * 规则（按执行顺序）：
 * 1. `Authorization:` / `Cookie:`（含 `Set-Cookie:`、`Proxy-Authorization:`）头：冒号之后整行遮蔽为 `***`；
 * 2. `Bearer xxx` → `Bearer ***`，`Basic xxx` → `Basic ***`；
 * 3. 键值形式 `key=value` / `key: value` / JSON `"key":"value"`（键不区分大小写）：
 *    password / passwd / pwd / token / secret / api_key / apikey / authorization / cookie 的值 → `***`。
 *    键按「以敏感词结尾」匹配，所以 access_token、refresh_token、id_token、client_secret、
 *    clientSecret、X-Api-Key 等也在其内；带引号的值保留引号形态；
 * 4. Google 令牌形态：`ya29.`（访问令牌）、`1//`（刷新令牌）、`GOCSPX-`（客户端密钥）→ 保留前缀 + `***`；
 * 5. URL（任意 `scheme://`，如 http / https / webdav / content）：去掉 userinfo（`user:pass@`）、
 *    去掉 query 与 fragment，path 截到 [MAX_URL_PATH_CHARS] 个字符（超出加 `…`）；
 * 6. 邮箱 → `***@域名`（WebDAV 用户名常是邮箱）。
 * 普通文本原样保留。
 *
 * 设计约束：
 * - 对常规日志文本幂等：`redact(redact(x)) == redact(x)`，上传前整份再跑一遍不会改变已脱敏的内容
 *   （`a@b.c@d.e` 这类畸形的嵌套输入再跑一遍可能多遮蔽一点，只会更严格）；
 * - 逐行独立：任何规则都不跨行，所以对整份多行日志调用等价于逐行调用；
 * - 线性时间：不使用无界的嵌套量词（引号内转义序列的重复有上限，见 [MAX_ESCAPES_IN_VALUE]），
 *   也不依赖 `\s` `\b` `\w`（Java 与 JS 的语义有差异），只用显式字符类与单字符后行断言，
 *   Worker 处理最大 3MB 的日志不会出现灾难性回溯；
 * - 大小写：用内联 `(?i)`（仅 ASCII），不用 Kotlin 的 RegexOption.IGNORE_CASE——后者会附带 UNICODE_CASE，
 *   与 JS 不一致。（Android 的 java.util.regex 底层是 ICU，其大小写不敏感是 Unicode 感知的，
 *   如 `ſ` 视同 `s`——对这类罕见字符只会多遮蔽、不会少；六条正则已在 ICU 上验证过能编译、结果一致。）
 *
 * 局限：值以空白、`, ; & ) ] }` 与引号为界，含这些字符的明文密码只会被遮蔽到第一个分隔符；
 * 这是兜底手段，真正的防线仍是「代码里不要把凭据写进日志」。
 */
object LogRedactor {

    /** 被遮蔽的内容统一替换为该占位 */
    const val MASK = "***"

    /** URL 的 path 最多保留的字符数，超出部分以 `…` 代替 */
    const val MAX_URL_PATH_CHARS = 80

    /** 带引号的值里，最多按转义语义解析的转义序列个数（见 [KEY_VALUE]） */
    private const val MAX_ESCAPES_IN_VALUE = 64

    /** 规则 1：敏感请求头，从冒号后整行遮蔽（值至少 1 个字符才算有值） */
    private val HEADER_LINE = Regex(
        """(?i)(authorization|cookie)([ \t]*:[ \t]*)[^ \t\r\n][^\r\n]*""",
    )

    /** 规则 2：Bearer / Basic 凭据（token68 字符集 + 可选的 `=` 填充） */
    private val AUTH_SCHEME = Regex(
        """(?i)(?<![A-Za-z0-9_])(bearer|basic)([ \t]+)[A-Za-z0-9\-._~+/]+=*""",
    )

    /**
     * 规则 3：键值。分三组：敏感词、分隔符（含键尾的引号与转义引号）、值。
     * 值依次尝试：转义双引号 `\"..\"`（嵌在 JSON 字符串里的 JSON）、双引号、单引号、无引号。
     * 带引号的分支都允许缺少收尾引号（被截断的日志），因此一旦开头匹配就不会失败回溯，整体保持线性；
     * 引号内的转义序列最多按转义语义解析 [MAX_ESCAPES_IN_VALUE] 次，之后一律当普通字符直接吃到收尾引号——
     * java.util.regex 对「组的重复」是递归实现，转义个数不设上限会让超长的值触发 StackOverflowError。
     */
    private val KEY_VALUE = Regex(
        """(?i)(password|passwd|pwd|token|secret|api[_-]?key|authorization|cookie)""" +
            """(\\?["']?[ \t]*[:=][ \t]*)""" +
            """(\\"[^\\\r\n]*(?:\\[^"\r\n][^\\\r\n]*){0,$MAX_ESCAPES_IN_VALUE}(?:\\"|[^\r\n]*)""" +
            """|"[^"\\\r\n]*(?:\\[^\r\n][^"\\\r\n]*){0,$MAX_ESCAPES_IN_VALUE}[^"\r\n]*"?""" +
            """|'[^'\\\r\n]*(?:\\[^\r\n][^'\\\r\n]*){0,$MAX_ESCAPES_IN_VALUE}[^'\r\n]*'?""" +
            """|[^ \t\r\n,;&)\]}"'\\]+)""",
    )

    /** 规则 4：Google 令牌形态（前缀不区分大小写，前面不能紧挨字母数字） */
    private val GOOGLE_TOKEN = Regex(
        """(?i)(?<![A-Za-z0-9])(ya29\.|1//|GOCSPX-)[A-Za-z0-9._\-]+""",
    )

    /** 规则 5：scheme://authority/path?query#fragment（authority 可能含 userinfo） */
    private val URL = Regex(
        """(?<![A-Za-z0-9+.\-])([A-Za-z][A-Za-z0-9+.\-]*)://([^ \t\r\n/?#"'<>]*)([^ \t\r\n?#"'<>]*)""" +
            """(?:\?[^ \t\r\n#"'<>]*)?(?:#[^ \t\r\n"'<>]*)?""",
    )

    /** 规则 6：邮箱（域名至少含一个点，避免误伤 `Foo@1a2b3c` 这类对象 toString） */
    private val EMAIL = Regex(
        """(?<![A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]{1,64}@([A-Za-z0-9\-]+(?:\.[A-Za-z0-9\-]+)+)""",
    )

    /** 逐行脱敏仍失败时，该行整体替换为这段占位：宁可丢内容也不留明文 */
    const val REDACT_FAILED_PLACEHOLDER = "[脱敏失败，该行已丢弃]"

    /**
     * 与 [redact] 相同，但**永不抛出**：整段脱敏出错（如正则引擎的 StackOverflowError）时退化为逐行脱敏，
     * 单行仍失败则该行替换为 [REDACT_FAILED_PLACEHOLDER]。写盘与上传都走这个入口。
     */
    fun redactSafely(text: String): String {
        try {
            return redact(text)
        } catch (_: Exception) {
            // 退化为逐行
        } catch (_: StackOverflowError) {
            // 同上
        } catch (_: LinkageError) {
            // 正则在某个运行时上编译失败（类初始化出错）：逐行也会失败，最终全部落到占位——安全但没有内容
        }
        return text.split('\n').joinToString(separator = "\n") { line ->
            try {
                redact(line)
            } catch (_: Exception) {
                REDACT_FAILED_PLACEHOLDER
            } catch (_: StackOverflowError) {
                REDACT_FAILED_PLACEHOLDER
            } catch (_: LinkageError) {
                REDACT_FAILED_PLACEHOLDER
            }
        }
    }

    /** 对一行（或一段多行）文本脱敏，返回脱敏后的文本；没有敏感内容时原样返回 */
    fun redact(line: String): String {
        if (line.isEmpty()) return line
        var s = line
        s = HEADER_LINE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = AUTH_SCHEME.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + MASK }
        s = KEY_VALUE.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + maskKeyValue(m.groupValues[3]) }
        s = GOOGLE_TOKEN.replace(s) { m -> m.groupValues[1] + MASK }
        s = URL.replace(s) { m -> maskUrl(m.groupValues[1], m.groupValues[2], m.groupValues[3]) }
        s = EMAIL.replace(s) { m -> "$MASK@${m.groupValues[1]}" }
        return s
    }

    /** 键值里被遮蔽的值：保留引号形态；本来就是空引号串（没有内容）则原样返回 */
    private fun maskKeyValue(value: String): String {
        if (value.startsWith("\\\"")) {
            val closed = value.length >= 4 && value.endsWith("\\\"")
            val inner = value.length - 2 - (if (closed) 2 else 0)
            return if (inner <= 0) value else "\\\"" + MASK + (if (closed) "\\\"" else "")
        }
        val quote = value[0]
        if (quote == '"' || quote == '\'') {
            val closed = value.length >= 2 && value.endsWith(quote)
            val inner = value.length - 1 - (if (closed) 1 else 0)
            return if (inner <= 0) value else quote + MASK + (if (closed) quote.toString() else "")
        }
        return MASK
    }

    /** 去掉 userinfo（取最后一个 `@` 之后的部分）、query、fragment，path 超长则截断 */
    private fun maskUrl(scheme: String, authority: String, path: String): String {
        val host = authority.substringAfterLast('@')
        var shownPath = path
        if (shownPath.length > MAX_URL_PATH_CHARS) {
            var end = MAX_URL_PATH_CHARS
            // 不把代理对劈成两半
            if (Character.isHighSurrogate(shownPath[end - 1])) end--
            shownPath = shownPath.substring(0, end) + "…"
        }
        return "$scheme://$host$shownPath"
    }
}
