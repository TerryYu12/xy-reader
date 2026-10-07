package com.xyreader.feedback

import android.os.Build
import com.xyreader.BuildConfig
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 一次反馈要上传的运行日志，分三段：
 * 1. [header]：设备与应用信息（应用版本 / versionCode、厂商、型号、Android 版本与 SDK_INT、时间）；
 * 2. [appLog]：[AppLog.readAll] 读出的本地滚动日志；
 * 3. [logcat]：当前进程 logcat 的最近 [LOGCAT_LINES] 行，读不到时为 null（直接跳过这一段）。
 *
 * [toUploadLog] 把三段合成最终上传的文本。拆成纯数据 + 纯函数，便于单测与 [FeedbackUploader] 注入。
 */
class FeedbackReport(
    val header: String,
    val appLog: String,
    val logcat: String?,
) {

    /**
     * 合成上传用的日志文本：
     * - 整体再跑一遍 [LogRedactor.redactSafely]（本地写盘前已脱敏过一次，这里是上传前的第二道保险）；
     * - UTF-8 超过 [maxBytes] 时丢弃**最旧**的部分、只保留尾部；设备信息头始终保留，
     *   丢弃处留一行说明，方便开发者知道日志被截过。
     */
    fun toUploadLog(maxBytes: Int = MAX_LOG_BYTES): String {
        val head = LogRedactor.redactSafely(header)
        val body = LogRedactor.redactSafely(composeBody())
        return head + keepUtf8Tail(body, maxBytes - utf8Size(head))
    }

    private fun composeBody(): String = buildString {
        append("\n==== 应用日志（AppLog） ====\n")
        append(if (appLog.isEmpty()) "（无）\n" else appLog)
        if (logcat != null) {
            if (!endsWith('\n')) append('\n')
            append("\n==== ").append(LOGCAT_TITLE).append(" ====\n")
            append(logcat)
        }
    }

    companion object {
        /** 上传日志的 UTF-8 字节上限（Worker 的硬上限是 3MB，这里留余量） */
        const val MAX_LOG_BYTES = 2_900_000

        /** logcat 段落抓取的行数 */
        const val LOGCAT_LINES = 500

        /** logcat 段落标题 */
        const val LOGCAT_TITLE = "当前进程 logcat（最近 $LOGCAT_LINES 行）"

        /** logcat 子进程最长允许运行的时间 */
        private const val LOGCAT_TIMEOUT_MS = 5_000L

        private const val TRUNCATED_NOTICE = "…（日志过长，已丢弃较早的部分）\n"

        /** 找行首时最多向后看多少字节：避免为了对齐行首而丢掉太多 */
        private const val LINE_ALIGN_WINDOW = 4096

        /** 读取本机当前状态生成报告：设备信息 + AppLog + logcat。含磁盘与子进程 IO，请在后台线程调用。 */
        fun collect(): FeedbackReport = FeedbackReport(
            header = buildHeader(),
            appLog = AppLog.readAll(),
            logcat = readLogcatTail(),
        )

        /** 设备与应用信息头 */
        internal fun buildHeader(nowMillis: Long = System.currentTimeMillis()): String {
            val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(nowMillis))
            return buildString {
                append("==== 设备与应用信息 ====\n")
                append("应用：").append(FeedbackUploader.APP_ID).append(' ').append(BuildConfig.VERSION_NAME)
                    .append("（versionCode ").append(BuildConfig.VERSION_CODE).append("）\n")
                append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
                append("系统：Android ").append(Build.VERSION.RELEASE)
                    .append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n")
                append("时间：").append(time).append('\n')
            }
        }

        /**
         * 抓取当前进程 logcat 的最近 [lines] 行；任何失败（没有 logcat 可执行文件、被系统限制、超时、
         * 非零退出码）都返回 null，调用方直接跳过这一段。
         */
        internal fun readLogcatTail(lines: Int = LOGCAT_LINES): String? {
            var process: Process? = null
            return try {
                val pid = android.os.Process.myPid().toString()
                val started = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-d", "-v", "threadtime", "-t", lines.toString(), "--pid", pid),
                )
                process = started
                // 看门狗：logcat -d 正常几十毫秒就结束，卡住时强制结束，避免上传一直等
                val watchdog = Thread {
                    try {
                        if (!started.waitFor(LOGCAT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) started.destroyForcibly()
                    } catch (_: InterruptedException) {
                        // 主线程已读完并打断看门狗
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                val text = try {
                    started.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally {
                    watchdog.interrupt()
                }
                val exited = started.waitFor(1, TimeUnit.SECONDS)
                if (exited && started.exitValue() == 0 && text.isNotBlank()) text else null
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null // 极端环境下 native 方法不可用：同样跳过
            } finally {
                // 无论成败都不留子进程
                runCatching { process?.destroyForcibly() }
            }
        }

        private fun utf8Size(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        /**
         * 只保留 [text] 的尾部，使 UTF-8 字节数（含开头的截断说明）不超过 [maxBytes]。
         * 起点落在字符边界上，并尽量对齐到行首；没超限时原样返回。
         */
        internal fun keepUtf8Tail(text: String, maxBytes: Int): String {
            val bytes = text.toByteArray(Charsets.UTF_8)
            if (bytes.size <= maxBytes) return text
            val noticeBytes = utf8Size(TRUNCATED_NOTICE)
            val budget = (maxBytes - noticeBytes).coerceAtLeast(0)
            var start = bytes.size - budget
            // 跳过 UTF-8 续字节（10xxxxxx），保证从完整字符开始
            while (start < bytes.size && (bytes[start].toInt() and 0xC0) == 0x80) start++
            // 在窗口内找下一个换行，从下一行开始，避免截出半行
            val windowEnd = minOf(bytes.size, start + LINE_ALIGN_WINDOW)
            for (i in start until windowEnd) {
                if (bytes[i] == '\n'.code.toByte()) {
                    start = i + 1
                    break
                }
            }
            return TRUNCATED_NOTICE + String(bytes, start, bytes.size - start, Charsets.UTF_8)
        }
    }
}
