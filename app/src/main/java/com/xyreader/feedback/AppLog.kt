package com.xyreader.feedback

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 应用日志门面：替代 `android.util.Log`，一边照常输出到 logcat，一边写入本地滚动日志。
 *
 * - 日志只存在本机（`cacheDir/logs/app.log`，最多约 2MB，自动轮转）；只有用户在「设置 → BUG 反馈」里
 *   主动提交时，才由 [FeedbackUploader] 读出（[readAll]）并上传；
 * - 写入前先脱敏（[LogRedactor]），单条消息 ≤ 2000 字符，每秒 ≤ 200 条，详见 [AppLogFile]；
 * - [init] 在 `ArkApp.onCreate()` 第一行调用，同时安装未捕获异常处理器：崩溃栈同步写盘后再交还给原处理器；
 * - 未调用 [init]（如单元测试）时只输出 logcat 并保留内存缓冲，不碰文件系统；
 * - 日志设施本身永远不抛异常。
 *
 * 不要记录书名全文、账号密码、令牌——脱敏只是兜底。
 */
object AppLog {

    /** 日志目录名（位于应用缓存目录下） */
    private const val LOG_DIR_NAME = "logs"

    /** 当前引擎；初始化前是仅内存的引擎 */
    @Volatile
    private var engine: AppLogFile = AppLogFile(dir = null)

    private val crashHandlerInstalled = AtomicBoolean(false)

    /** 初始化：日志落到 `cacheDir/logs/`，并安装未捕获异常处理器（只装一次）。可重复调用。 */
    fun init(context: Context) {
        val cacheDir = (context.applicationContext ?: context).cacheDir ?: return
        engine = AppLogFile(dir = File(cacheDir, LOG_DIR_NAME))
        installCrashHandler()
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { if (tr == null) Log.d(tag, msg) else Log.d(tag, msg, tr) }
        record('D', tag, msg, tr)
    }

    fun i(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { if (tr == null) Log.i(tag, msg) else Log.i(tag, msg, tr) }
        record('I', tag, msg, tr)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { if (tr == null) Log.w(tag, msg) else Log.w(tag, msg, tr) }
        record('W', tag, msg, tr)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        runCatching { if (tr == null) Log.e(tag, msg) else Log.e(tag, msg, tr) }
        record('E', tag, msg, tr)
    }

    /**
     * 读出全部本地日志（`app.log.1` 在前、`app.log` 在后）；未初始化或读失败时退回内存缓冲。
     * 会先等待排队中的写盘完成，所以刚打的日志也在里面。可能阻塞（受 2 秒超时保护），请在后台线程调用。
     */
    fun readAll(): String = try {
        engine.readAll()
    } catch (_: Exception) {
        ""
    }

    private fun record(level: Char, tag: String, msg: String, tr: Throwable?) {
        try {
            engine.append(level, tag, msg, tr)
        } catch (_: Exception) {
            // 日志设施自己的故障不能影响调用方
        }
    }

    /**
     * 安装未捕获异常处理器：先把崩溃栈同步写盘，再交还给原来的处理器（系统的「应用停止运行」流程照旧）。
     * Android 上原处理器恒不为空；没有原处理器（如纯 JVM 单测）时补上 JVM 默认的「打印栈」行为，
     * 否则装上我们的处理器反而会吞掉异常输出。
     */
    private fun installCrashHandler() {
        if (!crashHandlerInstalled.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                engine.appendCrash(thread.name, throwable)
            } catch (_: Exception) {
                // 写崩溃日志失败也要继续走原处理器
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                System.err.print("Exception in thread \"${thread.name}\" ")
                throwable.printStackTrace()
            }
        }
    }
}
