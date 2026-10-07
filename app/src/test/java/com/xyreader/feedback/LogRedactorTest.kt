package com.xyreader.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志脱敏规则单测（纯 JUnit，无 Android 依赖）。
 *
 * 这里的输入 / 期望值与中转 Worker 的测试向量（infra/log-relay/test/worker.test.mjs 里的
 * REDACTION_VECTORS）保持一致——两边规则必须等价，改一边时同步改另一边。
 */
class LogRedactorTest {

    /** 断言脱敏结果，并顺带断言幂等：对结果再脱敏一次不应再有变化 */
    private fun assertRedacted(expected: String, input: String) {
        val actual = LogRedactor.redact(input)
        assertEquals("输入：$input", expected, actual)
        assertEquals("二次脱敏应不变：$expected", expected, LogRedactor.redact(actual))
    }

    private fun assertUnchanged(input: String) = assertRedacted(input, input)

    // ---------- 规则 1：Authorization / Cookie / Set-Cookie 头 ----------

    @Test fun authorizationHeaderValueIsMasked() {
        assertRedacted("Authorization: ***", "Authorization: Bearer abc.def.ghi")
        assertRedacted("authorization:***", "authorization:Basic dXNlcjpwYXNz")
    }

    @Test fun authorizationHeaderInsideLogLineKeepsPrefix() {
        assertRedacted(
            "2026-10-07 10:00:00.000 D/OkHttp: Authorization: ***",
            "2026-10-07 10:00:00.000 D/OkHttp: Authorization: Bearer eyJhbGciOi.xxx.yyy",
        )
    }

    @Test fun cookieHeadersAreMaskedUntilEndOfLine() {
        assertRedacted("Cookie: ***", "Cookie: SID=abc123; theme=dark")
        assertRedacted("Set-Cookie: ***", "Set-Cookie: JSESSIONID=ABC; Path=/; HttpOnly")
        assertRedacted("Proxy-Authorization: ***", "Proxy-Authorization: Basic Zm9vOmJhcg==")
    }

    @Test fun emptyHeaderValueIsLeftAlone() {
        assertUnchanged("Cookie:")
        assertUnchanged("Cookie: ")
    }

    // ---------- 规则 2：Bearer / Basic ----------

    @Test fun bearerTokenIsMasked() {
        assertRedacted("got token Bearer *** here", "got token Bearer abc123xyz here")
        assertRedacted("bearer ***", "bearer abc123")
        assertRedacted("BEARER   ***", "BEARER   abc==")
    }

    @Test fun basicCredentialIsMasked() {
        assertRedacted("use Basic *** for auth", "use Basic dXNlcjpwYXNz for auth")
    }

    @Test fun alreadyMaskedBearerIsStable() {
        assertUnchanged("Bearer ***")
    }

    // ---------- 规则 3：键值 ----------

    @Test fun keyEqualsValueIsMasked() {
        assertRedacted("password=***", "password=hunter2")
        assertRedacted("passwd: ***", "passwd: hunter2")
        assertRedacted("pwd = ***", "pwd = hunter2")
    }

    @Test fun keysAreCaseInsensitive() {
        assertRedacted("PASSWORD=***&user=bob", "PASSWORD=Hunter2&user=bob")
        assertRedacted("Token=***", "Token=abc")
    }

    @Test fun everyListedKeyIsMasked() {
        val keys = listOf(
            "password", "passwd", "pwd", "token", "access_token", "refresh_token", "id_token",
            "client_secret", "secret", "authorization", "cookie", "api_key", "apikey",
        )
        for (key in keys) {
            assertRedacted("$key=***", "$key=value123")
            assertRedacted("$key: ***", "$key: value123")
            assertRedacted("\"$key\":\"***\"", "\"$key\":\"value123\"")
        }
    }

    @Test fun keysEndingWithSensitiveWordAreMaskedToo() {
        // data class 的 toString 是驼峰；HTTP 头常带前缀
        assertRedacted("clientSecret=***", "clientSecret=abc123")
        assertRedacted("refreshToken=***", "refreshToken=abc123")
        assertRedacted("X-Api-Key: ***", "X-Api-Key: KEY123")
        assertRedacted("API-KEY=***", "API-KEY=KEY123")
    }

    @Test fun nonSensitiveKeysAreKept() {
        assertUnchanged("tokenType=Bearer")
        assertUnchanged("key: value")
        assertUnchanged("secretary=alice")
    }

    @Test fun valueStopsAtDelimiters() {
        assertRedacted("(password=***)", "(password=hunter2)")
        assertRedacted("token=***,secret=***;pwd=***", "token=abc,secret=def;pwd=ghi")
        assertRedacted("password=***&user=bob", "password=hunter2&user=bob")
    }

    @Test fun emptyValueIsLeftAlone() {
        assertUnchanged("password=")
        assertUnchanged("password: ")
        assertUnchanged("{\"password\":\"\"}")
    }

    @Test fun jsonStringValuesKeepTheirQuotes() {
        assertRedacted("{\"password\":\"***\",\"user\":\"bob\"}", "{\"password\":\"hunter2\",\"user\":\"bob\"}")
        assertRedacted("{\"token\": \"***\", \"x\": 1}", "{\"token\": \"abc def\", \"x\": 1}")
        assertRedacted("'password': '***'", "'password': 'abc def'")
        assertRedacted("password=\"***\"", "password=\"abc def\"")
    }

    @Test fun jsonNonStringValueIsMasked() {
        assertRedacted("{\"password\": ***}", "{\"password\": 12345}")
    }

    @Test fun jsonEscapedInsideJsonStringIsMasked() {
        assertRedacted(
            "{\\\"password\\\":\\\"***\\\",\\\"user\\\":\\\"bob\\\"}",
            "{\\\"password\\\":\\\"hunter2\\\",\\\"user\\\":\\\"bob\\\"}",
        )
    }

    @Test fun unterminatedQuotedValueIsStillMasked() {
        assertRedacted("password=\"***", "password=\"abc")
    }

    @Test fun authorizationKeyWithBearerValueLeavesNothing() {
        assertRedacted("authorization=*** ***", "authorization=Bearer abc")
        assertRedacted("{\"authorization\":\"***\"}", "{\"authorization\":\"Bearer abc\"}")
    }

    @Test fun dataClassToStringIsMasked() {
        assertRedacted(
            "WebDavConfigEntity(name=nas, baseUrl=https://nas.local/dav, username=***@example.com, password=***)",
            "WebDavConfigEntity(name=nas, baseUrl=https://nas.local/dav, username=me@example.com, password=s3cr3t)",
        )
        assertRedacted(
            "GoogleDriveAccountEntity(clientId=123.apps.googleusercontent.com, clientSecret=***, refreshToken=***)",
            "GoogleDriveAccountEntity(clientId=123.apps.googleusercontent.com, " +
                "clientSecret=GOCSPX-xyz_123-ABC, refreshToken=1//0abcDEF-ghi_JKL)",
        )
    }

    // ---------- 规则 4：Google 令牌形态 ----------

    @Test fun googleAccessTokenKeepsPrefix() {
        assertRedacted("bad ya29.*** value", "bad ya29.A0ARrdaM-123_abc.def value")
    }

    @Test fun googleRefreshTokenKeepsPrefix() {
        assertRedacted("saved 1//*** ok", "saved 1//0gAbCdEf-gh_ij ok")
    }

    @Test fun googleClientSecretKeepsPrefix() {
        assertRedacted("secret GOCSPX-*** end", "secret GOCSPX-abc_123-XYZ end")
    }

    @Test fun googleTokensInsideLongerWordsAreNotTouched() {
        assertUnchanged("xya29.abc and a1//zzz")
    }

    @Test fun severalGoogleTokensInOneLine() {
        assertRedacted(
            "Token ya29.***, then 1//*** and GOCSPX-***",
            "Token ya29.abc, then 1//0abc and GOCSPX-abc.",
        )
    }

    // ---------- 规则 5：URL ----------

    @Test fun urlUserinfoQueryAndFragmentAreRemoved() {
        assertRedacted("https://dav.example.com/dav/", "https://user:pass@dav.example.com/dav/?token=abc#frag")
        assertRedacted("http://example.com/a/b", "http://example.com/a/b?x=1&y=2")
        assertRedacted("http://example.com", "http://example.com?x=1")
        assertRedacted("http://example.com", "http://example.com#frag")
    }

    @Test fun urlUserinfoEndsAtTheLastAtSign() {
        assertRedacted("https://dav.example.com/dav/", "https://user:p@ss@dav.example.com/dav/")
        assertRedacted("ftp://host/path", "ftp://user@host/path")
    }

    @Test fun urlSchemesAreNotLimitedToHttp() {
        assertRedacted("webdav://3/漫画/某某书.cbz", "webdav://3/漫画/某某书.cbz?x=1")
        assertRedacted(
            "content://com.android.externalstorage.documents/tree/primary%3AComics/document/x",
            "content://com.android.externalstorage.documents/tree/primary%3AComics/document/x#f",
        )
        assertUnchanged("file:///storage/emulated/0/Download/a.cbz")
    }

    @Test fun urlPathIsCutAt80Characters() {
        val path80 = "/" + "a".repeat(79)
        assertUnchanged("https://h.com$path80")
        val long = "/" + "a".repeat(120)
        assertRedacted("https://h.com$path80…", "https://h.com$long")
        // 截断后的结果再脱敏不再变化（幂等）
        assertUnchanged("https://h.com$path80…")
    }

    @Test fun urlCutDoesNotSplitSurrogatePairs() {
        // 第 80 个字符恰好是代理对的前半：整体退一位，不留下孤立的半个 emoji
        val path = "/" + "a".repeat(78) + "😀tail"
        val out = LogRedactor.redact("https://h.com$path")
        assertEquals("https://h.com/" + "a".repeat(78) + "…", out)
        assertFalse(out.any { Character.isSurrogate(it) })
    }

    @Test fun urlInsideQuotesAndParentheses() {
        assertRedacted("\"https://a.com/p\" and 'http://b.org/x'", "\"https://a.com/p?q=1\" and 'http://b.org/x'")
        assertRedacted("see (https://a.com/x now", "see (https://a.com/x?y=1) now")
    }

    @Test fun severalUrlsInOneLine() {
        assertRedacted("a https://x.com/1 b https://y.com/2", "a https://x.com/1?t=1 b https://u:p@y.com/2#z")
    }

    @Test fun webDavScannerStyleLogLine() {
        assertRedacted(
            "2026-10-07 10:00:00.000 W/WebDavScanner: PROPFIND 失败，跳过目录: https://nas.local/dav/漫画/, HTTP 500",
            "2026-10-07 10:00:00.000 W/WebDavScanner: PROPFIND 失败，跳过目录: " +
                "https://me@example.com:pw@nas.local/dav/漫画/, HTTP 500",
        )
    }

    // ---------- 规则 6：邮箱 ----------

    @Test fun emailLocalPartIsMasked() {
        assertRedacted("联系 ***@example.com 或 ***@sub.example.co.uk 。", "联系 me@example.com 或 a.b+c@sub.example.co.uk 。")
        assertRedacted("username=***@example.com", "username=me@example.com")
        assertRedacted("***@mail.jianguoyun.com", "a.b@mail.jianguoyun.com")
    }

    @Test fun halServiceNamesAreNotMistakenForEmails() {
        assertUnchanged("android.hardware.configstore@1.0::ISurfaceFlingerConfigs")
        assertUnchanged("configstore@1.0::X")
    }

    @Test fun nonEmailAtSignsAreKept() {
        assertUnchanged("Foo@1a2b3c object toString")
        assertUnchanged("user@localhost only")
        assertUnchanged("***@example.com")
    }

    // ---------- 普通文本 / 组合 / 边界 ----------

    @Test fun plainTextIsKeptVerbatim() {
        assertUnchanged("plain text with nothing sensitive")
        assertUnchanged("")
        assertUnchanged("2026-10-07 10:00:00.000 I/LibraryScanner: 仓库扫描结束 新增=3 更新=0 移除=1 耗时=1234ms")
        assertUnchanged("\tat com.xyreader.archive.PdfPageSource.close(PdfPageSource.kt:62)")
        assertUnchanged("java.io.IOException: closed")
        assertUnchanged("第 12 页渲染完成，用时 35ms，缓存命中 true")
    }

    @Test fun multipleSecretsInOneLine() {
        assertRedacted(
            "Mixed: password=*** https://host/x ***@example.com Bearer ***",
            "Mixed: password=abc https://u:p@host/x?y=1 me@example.com Bearer abcdef",
        )
    }

    @Test fun multiLineTextIsRedactedLineByLine() {
        val lines = listOf(
            "2026-10-07 10:00:00.000 E/Net: 请求失败 https://u:p@h.com/a?token=1",
            "java.io.IOException: password=hunter2",
            "\tat com.xyreader.Foo.bar(Foo.kt:1)",
            "Cookie: a=1; b=2",
            "ok",
        )
        val whole = LogRedactor.redact(lines.joinToString("\n"))
        val perLine = lines.joinToString("\n") { LogRedactor.redact(it) }
        assertEquals(perLine, whole)
        assertTrue(whole.contains("https://h.com/a"))
        assertTrue(whole.contains("password=***"))
        assertFalse(whole.contains("hunter2"))
        assertFalse(whole.contains("a=1"))
    }

    @Test fun windowsLineEndingsDoNotLeakTheNextLine() {
        assertEquals("Cookie: ***\r\nnext line", LogRedactor.redact("Cookie: a=1\r\nnext line"))
    }

    @Test fun redactSafelyBehavesLikeRedactOnNormalInput() {
        val input = "password=abc https://u:p@h.com/x?y=1 me@example.com"
        assertEquals(LogRedactor.redact(input), LogRedactor.redactSafely(input))
    }

    @Test fun veryLongEscapedValueDoesNotBlowTheStack() {
        // java.util.regex 对「组的重复」是递归实现；转义序列个数有上限，所以不会 StackOverflowError
        val value = "a\\\"".repeat(200_000)
        val input = "token=\"$value\" tail"
        val out = LogRedactor.redactSafely(input)
        assertTrue(out.startsWith("token=\"***"))
    }

    @Test fun largeLogIsRedactedInReasonableTime() {
        val line = "2026-10-07 10:00:00.123 W/WebDavScanner: PROPFIND 失败 https://u:p@nas.local/dav/?x=1 " +
            "password=abc Bearer abcdef token: ya29.a0AfH6SMBx me@example.com\n"
        val text = line.repeat(20_000) // 约 3MB
        val start = System.nanoTime()
        val out = LogRedactor.redact(text)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertFalse(out.contains("abcdef"))
        assertFalse(out.contains("ya29.a0"))
        assertTrue("3MB 日志脱敏耗时 ${elapsedMs}ms，疑似出现回溯爆炸", elapsedMs < 20_000)
    }
}
