package com.xyreader.feedback

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 滚动日志引擎（纯 JVM，无 Android 依赖；目录、阈值、时钟、时区、写盘线程都可注入，便于单测）。
 *
 * 一条日志从 [append] 进来后依次：
 * 1. **限流**：每个自然秒最多接收 [maxLinesPerSecond] 条，超出的丢弃并计数，下一秒开头先补一行
 *    「…已丢弃 N 条」（不占本秒名额）；
 * 2. **格式化**：`yyyy-MM-dd HH:mm:ss.SSS 级别/TAG: 消息`，异常的完整栈追加在后面；
 *    消息正文超过 [maxMessageChars] 截断并标注，栈超过 [maxThrowableChars] 兜底截断；
 * 3. **脱敏**：整条文本过 [redact]（默认 [LogRedactor.redactSafely]）；脱敏本身出错时宁可丢掉内容也不写明文；
 * 4. **内存环形缓冲**：最近 [recentCapacity] 行，落盘失败 / 目录不可用时仍可上传；
 * 5. **落盘**：交给单线程 [executor] 串行追加到 `dir/app.log`，写之前若会超过 [maxFileBytes]
 *    则先轮转为 `app.log.1`（旧的 `.1` 被覆盖），所以两个文件总量不超过 2 × [maxFileBytes]。
 *
 * [dir] 为 null 时只保留内存缓冲、不碰文件系统（应用尚未初始化时的退路）。
 * 所有磁盘错误都被吞掉：日志设施不能反过来拖垮应用。
 */
class AppLogFile(
    private val dir: File?,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxMessageChars: Int = DEFAULT_MAX_MESSAGE_CHARS,
    private val maxThrowableChars: Int = DEFAULT_MAX_THROWABLE_CHARS,
    private val maxLinesPerSecond: Int = DEFAULT_MAX_LINES_PER_SECOND,
    private val recentCapacity: Int = DEFAULT_RECENT_LINES,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val executor: Executor = newWriterExecutor(),
    private val redact: (String) -> String = LogRedactor::redactSafely,
) {

    private val timestampFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).withZone(zone)

    /** 当前日志文件（可能尚不存在） */
    val currentFile: File? get() = dir?.let { File(it, CURRENT_NAME) }

    /** 轮转出来的上一份日志文件（可能不存在） */
    val rotatedFile: File? get() = dir?.let { File(it, ROTATED_NAME) }

    // ---------- 限流状态（limitLock 保护） ----------
    private val limitLock = Any()
    private var currentSecond = Long.MIN_VALUE
    private var acceptedInSecond = 0
    private var droppedInSecond = 0

    // ---------- 内存环形缓冲（ringLock 保护） ----------
    private val ringLock = Any()
    private val ring = ArrayDeque<String>()

    // ---------- 文件读写（fileLock 保护）：写盘线程与崩溃线程可能同时写 ----------
    private val fileLock = Any()

    /**
     * 追加一条日志：立即进入内存缓冲，落盘异步进行。
     * [level] 取 D / I / W / E。被限流丢弃时什么也不做。
     */
    fun append(level: Char, tag: String, message: String, throwable: Throwable? = null) {
        val now = clock()
        val droppedBefore = admit(now)
        if (droppedBefore < 0) return
        val text = StringBuilder()
        if (droppedBefore > 0) {
            text.append(format(now, 'W', NOTICE_TAG, "…已丢弃 $droppedBefore 条", null)).append('\n')
        }
        text.append(format(now, level, tag, message, throwable))
        record(text.toString(), sync = false)
    }

    /**
     * 记录未捕获异常（崩溃）：绕过限流，**同步**写盘（不经 executor）——进程马上就要被杀，
     * 等不到异步任务。写之前先让已排队的普通日志落盘，保持先后顺序。
     */
    fun appendCrash(threadName: String, throwable: Throwable) {
        flush(CRASH_FLUSH_TIMEOUT_MS)
        val now = clock()
        record(format(now, 'E', CRASH_TAG, "未捕获异常 thread=$threadName", throwable), sync = true)
    }

    /**
     * 读出全部日志：先 `app.log.1` 后 `app.log`（时间顺序）。
     * 目录不可用、读失败或两个文件都为空时退回内存缓冲。
     */
    fun readAll(): String {
        if (dir != null) {
            flush(READ_FLUSH_TIMEOUT_MS)
            try {
                val text = synchronized(fileLock) {
                    readTextIfExists(rotatedFile) + readTextIfExists(currentFile)
                }
                if (text.isNotEmpty()) return text
            } catch (_: IOException) {
                // 读失败：退回内存缓冲
            } catch (_: SecurityException) {
                // 同上
            }
        }
        val lines = recentLines()
        return if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n")
    }

    /** 内存缓冲里最近的若干行（旧 → 新） */
    fun recentLines(): List<String> = synchronized(ringLock) { ring.toList() }

    /** 等待此前排队的落盘任务全部完成；超时返回 false。在写盘线程自身调用会等到超时。 */
    fun flush(timeoutMs: Long = READ_FLUSH_TIMEOUT_MS): Boolean {
        if (dir == null) return true
        return try {
            val latch = CountDownLatch(1)
            executor.execute { latch.countDown() }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: RejectedExecutionException) {
            true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * 限流判定。返回 -1 = 本条被丢弃；否则返回「进入新的一秒前被丢弃的条数」（0 = 没有需要补的提示）。
     * 时钟回拨也按「新的一秒」处理：只要秒数变了就重置计数。
     */
    private fun admit(now: Long): Int = synchronized(limitLock) {
        val second = Math.floorDiv(now, 1000L)
        var report = 0
        if (second != currentSecond) {
            report = droppedInSecond
            currentSecond = second
            acceptedInSecond = 0
            droppedInSecond = 0
        }
        if (acceptedInSecond >= maxLinesPerSecond) {
            droppedInSecond++
            return@synchronized -1
        }
        acceptedInSecond++
        report
    }

    /** 组装一条完整日志文本（可能多行），已脱敏；不含结尾换行 */
    private fun format(now: Long, level: Char, tag: String, message: String, throwable: Throwable?): String {
        val sb = StringBuilder()
        sb.append(timestampFormat.format(Instant.ofEpochMilli(now)))
            .append(' ').append(level).append('/').append(singleLine(tag)).append(": ")
            .append(clipMessage(message))
        if (throwable != null) {
            sb.append('\n').append(clipThrowable(throwable))
        }
        return try {
            redact(sb.toString())
        } catch (e: Exception) {
            "[脱敏失败，内容已丢弃：${e.javaClass.simpleName}]"
        } catch (e: LinkageError) {
            "[脱敏失败，内容已丢弃：${e.javaClass.simpleName}]"
        } catch (e: StackOverflowError) {
            "[脱敏失败，内容已丢弃：${e.javaClass.simpleName}]"
        }
    }

    private fun singleLine(tag: String): String = tag.replace('\n', ' ').replace('\r', ' ')

    private fun clipMessage(message: String): String {
        if (message.length <= maxMessageChars) return message
        val marker = "…[已截断，原长 ${message.length} 字符]"
        var keep = (maxMessageChars - marker.length).coerceAtLeast(0)
        // 不把代理对劈成两半
        if (keep > 0 && Character.isHighSurrogate(message[keep - 1])) keep--
        return message.substring(0, keep) + marker
    }

    private fun clipThrowable(throwable: Throwable): String {
        val trace = try {
            throwable.stackTraceToString().trimEnd().replace("\r\n", "\n")
        } catch (_: Exception) {
            // 异常的 toString / getMessage 自己抛了：至少留下类名
            throwable.javaClass.name
        }
        if (trace.length <= maxThrowableChars) return trace
        var keep = maxThrowableChars
        if (keep > 0 && Character.isHighSurrogate(trace[keep - 1])) keep--
        return trace.substring(0, keep) + "\n…[栈已截断，原长 ${trace.length} 字符]"
    }

    /** 进入内存缓冲，并（同步或异步）写盘 */
    private fun record(text: String, sync: Boolean) {
        synchronized(ringLock) {
            for (line in text.split('\n')) {
                ring.addLast(line)
            }
            while (ring.size > recentCapacity) ring.removeFirst()
        }
        if (dir == null) return
        val bytes = (text + "\n").toByteArray(Charsets.UTF_8)
        if (sync) {
            writeToDisk(bytes)
        } else {
            try {
                executor.execute { writeToDisk(bytes) }
            } catch (_: RejectedExecutionException) {
                // 执行器已关闭：只保留内存缓冲
            }
        }
    }

    /** 追加写入；写之前若会超过单文件上限则先轮转。任何失败都吞掉。 */
    private fun writeToDisk(bytes: ByteArray) {
        val directory = dir ?: return
        synchronized(fileLock) {
            try {
                // 缓存目录可能被「清除缓存」删掉，每次写之前自愈
                directory.mkdirs()
                val current = File(directory, CURRENT_NAME)
                val size = if (current.exists()) current.length() else 0L
                if (size > 0L && size + bytes.size > maxFileBytes) rotate(directory, current)
                FileOutputStream(current, true).use { it.write(bytes) }
            } catch (_: IOException) {
                // 落盘失败不影响运行：内存缓冲仍可用于上传
            } catch (_: SecurityException) {
                // 同上
            }
        }
    }

    private fun rotate(directory: File, current: File) {
        val rotated = File(directory, ROTATED_NAME)
        rotated.delete()
        if (!current.renameTo(rotated)) {
            // 改名失败（极少见）：直接丢弃当前文件，保证总量上限
            current.delete()
        }
    }

    private fun readTextIfExists(file: File?): String =
        if (file != null && file.exists()) file.readText(Charsets.UTF_8) else ""

    companion object {
        /** 当前日志文件名 */
        const val CURRENT_NAME = "app.log"

        /** 轮转后的上一份日志文件名 */
        const val ROTATED_NAME = "app.log.1"

        /** 单个文件上限 1MB，两个文件合计不超过 2MB */
        const val DEFAULT_MAX_FILE_BYTES = 1024L * 1024L

        /** 单条日志的消息正文上限（含截断标注） */
        const val DEFAULT_MAX_MESSAGE_CHARS = 2000

        /** 异常栈上限：远大于一般栈的长度，只防极端情况 */
        const val DEFAULT_MAX_THROWABLE_CHARS = 16_000

        /** 每个自然秒最多接收的条数 */
        const val DEFAULT_MAX_LINES_PER_SECOND = 200

        /** 内存环形缓冲保留的行数 */
        const val DEFAULT_RECENT_LINES = 500

        private const val NOTICE_TAG = "AppLog"
        private const val CRASH_TAG = "Crash"
        private const val READ_FLUSH_TIMEOUT_MS = 2000L
        private const val CRASH_FLUSH_TIMEOUT_MS = 500L

        /**
         * 默认写盘线程：同一时刻最多 1 个线程，保证串行；空闲 30 秒后自动退出，
         * 不会因为反复创建引擎（如测试里多次初始化）而泄漏线程。
         */
        fun newWriterExecutor(): Executor = ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable ->
                Thread(runnable, "AppLogWriter").apply { isDaemon = true }
            },
        ).apply { allowCoreThreadTimeOut(true) }
    }
}
