package com.xyreader.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反馈日志的组装、再脱敏与 2.9MB 上限裁剪单测（纯 JUnit：不触碰 Android 类）。
 * 设备信息头与 logcat 抓取依赖框架类，由 FeedbackUploaderTest（Robolectric）覆盖。
 */
class FeedbackReportTest {

    private val header = "==== 设备与应用信息 ====\n应用：xy-reader 0.5.3（versionCode 27）\n"

    private fun utf8Size(text: String) = text.toByteArray(Charsets.UTF_8).size

    // ---------- 组装 ----------

    @Test fun uploadLogHasHeaderAppLogAndLogcatSectionsInOrder() {
        val log = FeedbackReport(header, "app line 1\napp line 2\n", "logcat line\n").toUploadLog()
        val appAt = log.indexOf("==== 应用日志（AppLog） ====")
        val logcatAt = log.indexOf("==== ${FeedbackReport.LOGCAT_TITLE} ====")
        assertTrue("设备信息头在最前面", log.startsWith(header))
        assertTrue(appAt > header.length)
        assertTrue(logcatAt > appAt)
        assertTrue(log.indexOf("app line 2") in appAt until logcatAt)
        assertTrue(log.trimEnd().endsWith("logcat line"))
        assertEquals("当前进程 logcat（最近 500 行）", FeedbackReport.LOGCAT_TITLE)
    }

    @Test fun logcatSectionIsSkippedWhenUnavailable() {
        val log = FeedbackReport(header, "only app log\n", null).toUploadLog()
        assertTrue(log.contains("only app log"))
        assertFalse(log.contains("logcat"))
    }

    @Test fun emptyAppLogIsMarked() {
        val log = FeedbackReport(header, "", null).toUploadLog()
        assertTrue(log.contains("（无）"))
    }

    @Test fun appLogWithoutTrailingNewlineStillSeparatesLogcatSection() {
        val log = FeedbackReport(header, "no newline at end", "x\n").toUploadLog()
        assertTrue(log.contains("no newline at end\n\n==== ${FeedbackReport.LOGCAT_TITLE}"))
    }

    // ---------- 再脱敏 ----------

    @Test fun wholeReportIsRedactedAgain() {
        val appLog = "2026-10-07 10:00:00.000 W/X: password=hunter2 https://u:p@h.com/a?token=1\n"
        val logcat = "10-07 10:00:00.000  123  456 D OkHttp: Authorization: Bearer abc.def\n" +
            "10-07 10:00:00.001  123  456 I Account: me@example.com\n"
        val log = FeedbackReport(header, appLog, logcat).toUploadLog()
        for (secret in listOf("hunter2", "u:p@", "token=1", "Bearer abc", "abc.def", "me@example.com")) {
            assertFalse("不应出现 $secret", log.contains(secret))
        }
        assertTrue(log.contains("password=***"))
        assertTrue(log.contains("https://h.com/a"))
        assertTrue(log.contains("Authorization: ***"))
        assertTrue(log.contains("***@example.com"))
    }

    // ---------- 上限裁剪 ----------

    @Test fun reportUnderTheLimitIsKeptWhole() {
        val appLog = (1..50).joinToString("\n", postfix = "\n") { "line $it" }
        val log = FeedbackReport(header, appLog, "tail\n").toUploadLog(maxBytes = 100_000)
        assertFalse(log.contains("已丢弃较早的部分"))
        assertTrue(log.contains("line 1\n"))
        assertTrue(log.contains("line 50\n"))
    }

    @Test fun overTheLimitDropsTheOldestPartAndKeepsHeaderAndTail() {
        val appLog = (1..3000).joinToString("\n", postfix = "\n") { "第 $it 行：日志内容，用于撑大体积" }
        val limit = 8_000
        val log = FeedbackReport(header, appLog, "最后的 logcat\n").toUploadLog(maxBytes = limit)
        assertTrue("UTF-8 大小 ${utf8Size(log)} 应不超过 $limit", utf8Size(log) <= limit)
        assertTrue("设备信息头始终保留", log.startsWith(header))
        assertTrue("应说明日志被截过", log.contains("日志过长，已丢弃较早的部分"))
        assertTrue("尾部（最新）保留", log.contains("第 3000 行"))
        assertTrue(log.trimEnd().endsWith("最后的 logcat"))
        assertFalse("最旧的部分被丢弃", log.contains("第 1 行："))
    }

    @Test fun cutStartsAtALineBoundary() {
        val appLog = (1..2000).joinToString("\n", postfix = "\n") { "entry-$it-" + "x".repeat(40) }
        val log = FeedbackReport(header, appLog, null).toUploadLog(maxBytes = 5_000)
        val firstKeptLine = log.lines().first { it.startsWith("entry-") }
        assertTrue("被截出的第一行必须是完整的一行：$firstKeptLine", Regex("""entry-\d+-x{40}""").matches(firstKeptLine))
    }

    @Test fun cuttingNeverProducesBrokenCharacters() {
        val cjk = "漫画阅读器日志😀日本語한국어".repeat(400)
        for (limit in 600..640) {
            val log = FeedbackReport(header, cjk, null).toUploadLog(maxBytes = limit)
            assertTrue("limit=$limit size=${utf8Size(log)}", utf8Size(log) <= limit)
            assertFalse("limit=$limit 出现了替换字符", log.contains('�'))
            assertEquals("limit=$limit 往返编码必须无损", log, String(log.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        }
    }

    @Test fun defaultLimitIsBelowTheWorkerHardLimit() {
        assertTrue(FeedbackReport.MAX_LOG_BYTES <= 2_900_000)
        assertTrue("Worker 的硬上限是 3MB（3 * 1024 * 1024）", FeedbackReport.MAX_LOG_BYTES < 3 * 1024 * 1024)
        val huge = "日志行内容很长很长很长很长很长很长很长很长很长很长\n".repeat(60_000) // 约 4.5MB
        val log = FeedbackReport(header, huge, "tail\n").toUploadLog()
        assertTrue(utf8Size(log) <= FeedbackReport.MAX_LOG_BYTES)
        assertTrue(utf8Size(log) > FeedbackReport.MAX_LOG_BYTES - 200)
    }

    // ---------- keepUtf8Tail ----------

    @Test fun keepUtf8TailReturnsShortTextVerbatim() {
        assertEquals("abc\n", FeedbackReport.keepUtf8Tail("abc\n", 100))
    }

    @Test fun keepUtf8TailCountsBytesNotChars() {
        val text = "中".repeat(100) // 100 字符 = 300 字节
        val kept = FeedbackReport.keepUtf8Tail(text, 200)
        assertTrue(utf8Size(kept) <= 200)
        assertTrue(kept.endsWith("中"))
        assertTrue(kept.startsWith("…（日志过长，已丢弃较早的部分）\n"))
    }

    @Test fun keepUtf8TailHandlesTinyBudgets() {
        // 预算连截断说明都放不下：只返回说明，不抛异常
        val kept = FeedbackReport.keepUtf8Tail("x".repeat(1000), 5)
        assertTrue(kept.startsWith("…（日志过长"))
    }
}
