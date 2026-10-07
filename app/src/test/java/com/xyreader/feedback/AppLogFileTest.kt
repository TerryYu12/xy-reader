package com.xyreader.feedback

import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 滚动日志引擎单测（纯 JUnit，临时目录 + 可注入时钟 / 阈值 / 写盘线程，无 Android 依赖）。
 * 默认用「同步执行器」让落盘立即发生，测试结果确定；并发用例改用真实的单线程执行器。
 */
class AppLogFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 2026-10-07 12:34:56.000 UTC，整秒，便于推算限流的「自然秒」边界 */
    private val t0: Long = OffsetDateTime.of(2026, 10, 7, 12, 34, 56, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private var now: Long = t0 + 789

    private val directExecutor = Executor { it.run() }

    private fun logDir() = File(tmp.root, "logs")

    private fun newLog(
        dir: File? = logDir(),
        maxFileBytes: Long = AppLogFile.DEFAULT_MAX_FILE_BYTES,
        maxMessageChars: Int = AppLogFile.DEFAULT_MAX_MESSAGE_CHARS,
        maxThrowableChars: Int = AppLogFile.DEFAULT_MAX_THROWABLE_CHARS,
        maxLinesPerSecond: Int = 1_000_000,
        recentCapacity: Int = AppLogFile.DEFAULT_RECENT_LINES,
        executor: Executor = directExecutor,
        redact: (String) -> String = LogRedactor::redactSafely,
    ) = AppLogFile(
        dir = dir,
        maxFileBytes = maxFileBytes,
        maxMessageChars = maxMessageChars,
        maxThrowableChars = maxThrowableChars,
        maxLinesPerSecond = maxLinesPerSecond,
        recentCapacity = recentCapacity,
        clock = { now },
        zone = ZoneOffset.UTC,
        executor = executor,
        redact = redact,
    )

    private fun File.textOrEmpty() = if (exists()) readText(Charsets.UTF_8) else ""

    // ---------- 追加与格式 ----------

    @Test fun appendWritesOneFormattedLine() {
        val log = newLog()
        log.append('I', "Tag", "hello")
        assertEquals("2026-10-07 12:34:56.789 I/Tag: hello\n", log.currentFile!!.readText())
    }

    @Test fun levelsAreWrittenAsSingleLetters() {
        val log = newLog()
        for (level in listOf('D', 'I', 'W', 'E')) log.append(level, "T", "m")
        assertEquals(
            listOf("D/T: m", "I/T: m", "W/T: m", "E/T: m"),
            log.currentFile!!.readLines().map { it.substringAfter(" ", "").substringAfter(" ") },
        )
    }

    @Test fun throwableStackTraceIsAppendedInFull() {
        val log = newLog()
        val cause = IllegalStateException("root cause")
        log.append('E', "T", "打开失败", RuntimeException("boom", cause))
        val text = log.currentFile!!.readText()
        assertTrue(text.startsWith("2026-10-07 12:34:56.789 E/T: 打开失败\n"))
        assertTrue(text.contains("java.lang.RuntimeException: boom"))
        assertTrue(text.contains("\tat "))
        assertTrue("应包含 Caused by 链", text.contains("Caused by: java.lang.IllegalStateException: root cause"))
        assertTrue(text.endsWith("\n"))
    }

    @Test fun newlinesInTagAreFlattened() {
        val log = newLog()
        log.append('I', "a\nb", "m")
        assertEquals("2026-10-07 12:34:56.789 I/a b: m\n", log.currentFile!!.readText())
    }

    @Test fun lineIsRedactedBeforeItIsWritten() {
        val log = newLog()
        log.append('W', "T", "失败 password=hunter2 url=https://u:p@h.com/x?token=abc me@example.com")
        val text = log.currentFile!!.readText()
        assertFalse(text.contains("hunter2"))
        assertFalse(text.contains("u:p@"))
        assertFalse(text.contains("token=abc"))
        assertFalse(text.contains("me@"))
        assertTrue(text.contains("password=***"))
        assertTrue(text.contains("https://h.com/x"))
        assertTrue(text.contains("***@example.com"))
        // 内存缓冲里同样是脱敏后的
        assertFalse(log.recentLines().joinToString("\n").contains("hunter2"))
    }

    @Test fun stackTraceMessageIsRedactedToo() {
        val log = newLog()
        log.append('E', "T", "x", IllegalStateException("bad Authorization: Bearer secret-token-123"))
        assertFalse(log.currentFile!!.readText().contains("secret-token-123"))
    }

    @Test fun failingRedactorNeverLeaksPlainText() {
        val log = newLog(redact = { throw IllegalStateException("boom") })
        log.append('E', "T", "password=hunter2")
        val text = log.currentFile!!.readText()
        assertFalse(text.contains("hunter2"))
        assertTrue(text.contains("脱敏失败"))

        val overflow = newLog(dir = File(tmp.root, "logs2"), redact = { throw StackOverflowError() })
        overflow.append('E', "T", "password=hunter2")
        assertFalse(overflow.currentFile!!.readText().contains("hunter2"))
    }

    // ---------- 单条截断 ----------

    @Test fun overlongMessageIsTruncatedAndMarked() {
        val log = newLog()
        log.append('I', "T", "x".repeat(5000))
        val line = log.currentFile!!.readLines().single()
        val message = line.substringAfter("I/T: ")
        assertEquals("消息正文（含截断标注）应恰好 2000 字符", 2000, message.length)
        assertTrue(message.endsWith("…[已截断，原长 5000 字符]"))
        assertTrue(message.startsWith("xxxxxxxx"))
    }

    @Test fun messageOfExactlyTheLimitIsKept() {
        val log = newLog()
        val message = "y".repeat(2000)
        log.append('I', "T", message)
        assertEquals(message, log.currentFile!!.readLines().single().substringAfter("I/T: "))
    }

    @Test fun truncationDoesNotSplitSurrogatePairs() {
        val log = newLog(maxMessageChars = 30)
        // 标注占用若干字符，剩余额度的边界恰好落在 emoji 中间时应整体退一位
        for (padding in 0..8) {
            log.append('I', "T", "a".repeat(padding) + "😀".repeat(40))
        }
        val text = log.currentFile!!.readText()
        // 孤立的代理字符编码成 UTF-8 会变成 '?'；这里不应出现
        assertFalse(text.contains('?'))
        assertFalse(text.contains('�'))
    }

    @Test fun overlongStackTraceIsCappedWithMarker() {
        val log = newLog(maxThrowableChars = 300)
        log.append('E', "T", "m", RuntimeException("e".repeat(1000)))
        val text = log.currentFile!!.readText()
        assertTrue(text.contains("…[栈已截断，原长 "))
        assertTrue("栈部分应被截到上限附近", text.length < 600)
    }

    // ---------- 轮转与总量上限 ----------

    @Test fun rotatesToDotOneAndKeepsTotalSizeBounded() {
        val maxFileBytes = 400L
        val log = newLog(maxFileBytes = maxFileBytes)
        repeat(40) { i -> log.append('I', "T", "line-%02d %s".format(i, "x".repeat(20))) }

        val current = log.currentFile!!
        val rotated = log.rotatedFile!!
        assertTrue("应已轮转出 app.log.1", rotated.exists())
        assertTrue(current.length() in 1..maxFileBytes)
        assertTrue(rotated.length() in 1..maxFileBytes)
        assertTrue("总量不超过 2 倍阈值", current.length() + rotated.length() <= 2 * maxFileBytes)

        val numbers = log.readAll().lines().filter { it.isNotBlank() }.map { it.substringAfter("line-").take(2).toInt() }
        assertTrue("最旧的几条应已被轮转丢弃", numbers.first() > 0)
        assertEquals("最新一条必须在", 39, numbers.last())
        assertEquals("顺序必须是严格递增的时间顺序", numbers.sorted(), numbers)
        assertEquals("没有重复", numbers.distinct(), numbers)
        assertEquals("连续无缺口", (numbers.first()..39).toList(), numbers)
    }

    @Test fun readAllPutsRotatedFileBeforeCurrentFile() {
        // 单条 32 字节，阈值 100：每个文件最多 3 条，5 条日志 = .1 里 3 条 + 当前文件 2 条
        val log = newLog(maxFileBytes = 100)
        for (i in 1..5) log.append('I', "T", "m$i")
        val rotatedLines = log.rotatedFile!!.readLines().map { it.substringAfter("m") }
        val currentLines = log.currentFile!!.readLines().map { it.substringAfter("m") }
        assertEquals(rotatedLines + currentLines, log.readAll().lines().filter { it.isNotBlank() }.map { it.substringAfter("m") })
        assertEquals("5", currentLines.last())
    }

    @Test fun appendAfterRotationStartsAFreshCurrentFile() {
        // 单条 32 字节，阈值 70：每个文件最多 2 条，第 3 条触发轮转
        val log = newLog(maxFileBytes = 70)
        for (i in 1..3) log.append('I', "T", "m$i")
        assertTrue(log.rotatedFile!!.exists())
        assertEquals(listOf("m3"), log.currentFile!!.readLines().map { it.substringAfter("T: ") })
    }

    @Test fun existingFileSizeCountsTowardsRotationAfterRestart() {
        val dir = logDir().apply { mkdirs() }
        File(dir, AppLogFile.CURRENT_NAME).writeText("old line\n".repeat(10)) // 90 字节，模拟上次进程留下的日志
        val log = newLog(maxFileBytes = 100)
        log.append('I', "T", "new")
        assertEquals("旧内容被轮转到 .1", "old line\n".repeat(10), log.rotatedFile!!.readText())
        assertEquals(listOf("2026-10-07 12:34:56.789 I/T: new"), log.currentFile!!.readLines())
    }

    @Test fun logDirectoryIsRecreatedIfRemoved() {
        val log = newLog()
        log.append('I', "T", "first")
        logDir().deleteRecursively()
        log.append('I', "T", "second")
        assertEquals(listOf("2026-10-07 12:34:56.789 I/T: second"), log.currentFile!!.readLines())
    }

    // ---------- 每秒限流 ----------

    @Test fun excessLinesPerSecondAreDroppedAndReportedInTheNextSecond() {
        val log = newLog(maxLinesPerSecond = 5)
        now = t0
        repeat(8) { i -> log.append('I', "T", "burst-$i") }
        assertEquals("同一秒内只接收 5 条", 5, log.currentFile!!.readLines().size)

        now = t0 + 1000
        log.append('I', "T", "after")
        val lines = log.currentFile!!.readLines()
        assertEquals(7, lines.size)
        assertEquals("2026-10-07 12:34:57.000 W/AppLog: …已丢弃 3 条", lines[5])
        assertEquals("2026-10-07 12:34:57.000 I/T: after", lines[6])
        assertEquals("提示行进入内存缓冲", lines.takeLast(2), log.recentLines().takeLast(2))
    }

    @Test fun limitIsPerNaturalSecondNotPerSlidingWindow() {
        val log = newLog(maxLinesPerSecond = 2)
        now = t0 + 900
        log.append('I', "T", "a")
        log.append('I', "T", "b")
        log.append('I', "T", "dropped") // 仍在同一自然秒内
        now = t0 + 999
        log.append('I', "T", "dropped-too")
        now = t0 + 1000 // 跨秒：计数清零，且补提示
        log.append('I', "T", "c")
        log.append('I', "T", "d")
        val messages = log.currentFile!!.readLines().map { it.substringAfter("/").substringAfter(": ") }
        assertEquals(listOf("a", "b", "…已丢弃 2 条", "c", "d"), messages)
    }

    @Test fun noNoticeWhenNothingWasDropped() {
        val log = newLog(maxLinesPerSecond = 3)
        now = t0
        repeat(3) { log.append('I', "T", "x") }
        now = t0 + 1000
        log.append('I', "T", "y")
        assertFalse(log.currentFile!!.readText().contains("已丢弃"))
    }

    @Test fun noticeIsEmittedOnlyOnce() {
        val log = newLog(maxLinesPerSecond = 1)
        now = t0
        repeat(4) { log.append('I', "T", "x") }
        now = t0 + 1000
        log.append('I', "T", "y")
        now = t0 + 2000
        log.append('I', "T", "z")
        assertEquals(1, log.currentFile!!.readText().split("已丢弃").size - 1)
    }

    @Test fun backwardsClockStartsANewBucket() {
        val log = newLog(maxLinesPerSecond = 1)
        now = t0 + 5000
        log.append('I', "T", "a")
        log.append('I', "T", "dropped")
        now = t0 // 用户把时间往回拨
        log.append('I', "T", "b")
        val text = log.currentFile!!.readText()
        assertTrue(text.contains("…已丢弃 1 条"))
        assertTrue(text.trimEnd().endsWith("b"))
    }

    // ---------- 崩溃：同步写盘 ----------

    private class ManualExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeFirst().run()
        }
    }

    @Test fun crashIsWrittenSynchronouslyWithoutTheExecutorAndBypassesTheLimiter() {
        val executor = ManualExecutor()
        val log = newLog(maxLinesPerSecond = 1, executor = executor)
        now = t0
        log.append('I', "T", "queued")
        log.append('I', "T", "dropped by limiter")
        assertFalse("普通日志此时还在队列里", log.currentFile!!.exists())

        log.appendCrash("main", IllegalStateException("kaboom"))
        val onDisk = log.currentFile!!.readText()
        assertTrue("崩溃栈必须立刻落盘，不经执行器", onDisk.contains("E/Crash: 未捕获异常 thread=main"))
        assertTrue(onDisk.contains("java.lang.IllegalStateException: kaboom"))
        assertFalse("排队的普通日志尚未执行", onDisk.contains("queued"))

        executor.runAll()
        val text = log.currentFile!!.readText()
        assertTrue(text.contains("I/T: queued"))
        assertFalse(text.contains("dropped by limiter"))
    }

    @Test fun crashWaitsForQueuedLinesSoOrderIsPreserved() {
        // 真实单线程执行器：崩溃前先 flush，已排队的日志在崩溃记录之前
        val log = newLog(executor = AppLogFile.newWriterExecutor())
        repeat(50) { i -> log.append('I', "T", "n-$i") }
        log.appendCrash("main", RuntimeException("boom"))
        val lines = log.currentFile!!.readLines()
        val crashIndex = lines.indexOfFirst { it.contains("E/Crash:") }
        val lastNormal = lines.indexOfLast { it.contains("I/T: n-49") }
        assertTrue(lastNormal in 0 until crashIndex)
    }

    // ---------- 内存缓冲与读取 ----------

    @Test fun memoryRingKeepsTheMostRecent500Lines() {
        val log = newLog()
        repeat(700) { i -> log.append('I', "T", "msg-$i") }
        val recent = log.recentLines()
        assertEquals(500, recent.size)
        assertTrue(recent.first().endsWith("msg-200"))
        assertTrue(recent.last().endsWith("msg-699"))
    }

    @Test fun stackTraceLinesCountAsSeparateLinesInTheRing() {
        val log = newLog(recentCapacity = 5)
        log.append('E', "T", "m", RuntimeException("boom"))
        assertEquals(5, log.recentLines().size)
        assertTrue(log.recentLines().any { it.startsWith("\tat ") })
    }

    @Test fun withoutDirectoryOnlyTheMemoryRingIsUsed() {
        val log = newLog(dir = null)
        log.append('I', "T", "kept in memory")
        assertNull(log.currentFile)
        assertEquals("2026-10-07 12:34:56.789 I/T: kept in memory\n", log.readAll())
        assertTrue(log.flush())
        assertFalse(tmp.root.resolve("logs").exists())
    }

    @Test fun readAllReturnsEmptyStringWhenNothingWasLogged() {
        assertEquals("", newLog().readAll())
        assertEquals("", newLog(dir = null).readAll())
    }

    @Test fun readAllFallsBackToMemoryWhenTheFileCannotBeRead() {
        // app.log 被一个同名目录占着：既写不进去也读不出来，但内存缓冲仍在
        val dir = logDir().apply { mkdirs() }
        File(dir, AppLogFile.CURRENT_NAME).mkdirs()
        val log = newLog()
        log.append('I', "T", "still uploadable")
        assertEquals("2026-10-07 12:34:56.789 I/T: still uploadable\n", log.readAll())
    }

    @Test fun readAllFallsBackToMemoryWhenFilesWereDeleted() {
        val log = newLog()
        log.append('I', "T", "x")
        logDir().deleteRecursively()
        assertEquals("2026-10-07 12:34:56.789 I/T: x\n", log.readAll())
    }

    // ---------- 真实单线程执行器 ----------

    @Test fun flushWaitsForAllQueuedWrites() {
        val log = newLog(executor = AppLogFile.newWriterExecutor())
        repeat(300) { i -> log.append('I', "T", "w-$i") }
        assertTrue(log.flush(10_000))
        assertEquals(300, log.currentFile!!.readLines().size)
    }

    @Test fun readAllSeesLinesThatAreStillQueued() {
        val log = newLog(executor = AppLogFile.newWriterExecutor())
        repeat(100) { i -> log.append('I', "T", "r-$i") }
        val lines = log.readAll().lines().filter { it.isNotBlank() }
        assertEquals(100, lines.size)
        assertTrue(lines.last().endsWith("r-99"))
    }

    @Test fun concurrentAppendsAreAllRecordedAndNeverInterleaved() {
        val log = newLog(executor = AppLogFile.newWriterExecutor())
        val threads = (0 until 4).map { t ->
            Thread { repeat(150) { i -> log.append('I', "T", "t$t-$i") } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(log.flush(10_000))
        val lines = log.currentFile!!.readLines()
        assertEquals(600, lines.size)
        val pattern = Regex("""2026-10-07 12:34:56\.789 I/T: t[0-3]-\d+""")
        assertTrue("每行都应完整", lines.all { pattern.matches(it) })
        assertEquals("没有丢失也没有重复", 600, lines.toSet().size)
    }

    @Test fun defaultWriterExecutorUsesASingleDaemonThread() {
        val names = mutableSetOf<String>()
        val daemon = AtomicInteger(0)
        val executor = AppLogFile.newWriterExecutor()
        val latch = java.util.concurrent.CountDownLatch(20)
        repeat(20) {
            executor.execute {
                synchronized(names) { names += Thread.currentThread().name }
                if (Thread.currentThread().isDaemon) daemon.incrementAndGet()
                latch.countDown()
            }
        }
        assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(setOf("AppLogWriter"), names)
        assertEquals(20, daemon.get())
    }

}
