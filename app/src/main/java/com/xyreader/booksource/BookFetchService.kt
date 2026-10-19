package com.xyreader.booksource

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.xyreader.MainActivity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 书源「整本抓取」的前台服务。
 *
 * 为什么需要它：一本长篇要抓几分钟，放在页面的协程里时用户一退到后台就可能被系统回收，
 * 抓到一半的整本会白费。前台服务 + 常驻通知把这段工作从页面生命周期里摘出来，
 * 并在通知栏给出「第 n/m 章」进度与取消入口。
 *
 * 行为约定（全部由 [LegadoBookDownloader] 既有语义保证，本服务只做编排）：
 * - **不产出半本书**：失败/取消都不入库、不留临时文件；
 * - 进度与结局写入 [BookFetchState]（进程级），页面只观察它；
 * - 通知不可划掉（ongoing）；点「取消」→ 取消协程 → 状态置 CANCELLED；
 * - 通知权限被拒（Android 13+）不影响抓取：前台服务照常运行，只是不显示通知。
 *
 * 前台服务类型选 `dataSync`（依据见 `docs/verification/2026-10-18-book-fetch-foreground-service.md`）：
 * 抓整本本质是一次可能持续数分钟的数据同步；targetSdk 34 要求声明类型并配套
 * `FOREGROUND_SERVICE_DATA_SYNC` 权限。
 */
class BookFetchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + workDispatcher)
    private val jobs = ConcurrentHashMap<String, Job>()
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_CANCEL -> handleCancel(intent)
            else -> if (jobs.isEmpty()) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handleStart(intent: Intent) {
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL) ?: return
        if (jobs.containsKey(bookUrl)) return // 同一本书已在抓，忽略重复请求
        val sourceUrl = intent.getStringExtra(EXTRA_SOURCE_URL) ?: return
        val book = SourceBook(
            name = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
            bookUrl = bookUrl,
            sourceUrl = sourceUrl,
            sourceName = intent.getStringExtra(EXTRA_SOURCE_NAME).orEmpty(),
            author = intent.getStringExtra(EXTRA_AUTHOR),
        )
        // startForegroundService 之后必须尽快 startForeground，否则系统会在约 5 秒后杀掉服务。
        val notification = buildNotification(book, done = 0, total = 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId(bookUrl),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(notificationId(bookUrl), notification)
        }
        BookFetchState.update(BookFetchStatus(bookUrl, book.name, BookFetchPhase.RUNNING))
        // LAZY + 先入表再启动：避免「先启动（可能同步跑完并从表里移除）后入表」留下幽灵任务
        val job = scope.launch(start = CoroutineStart.LAZY) { runFetch(book) }
        jobs[bookUrl] = job
        job.start()
    }

    private fun handleCancel(intent: Intent) {
        val bookUrl = intent.getStringExtra(EXTRA_BOOK_URL) ?: return
        // 取消协程：download 会抛 CancellationException 并删除临时文件（不入库）。
        // 状态置位与通知清理在其 finally 里统一完成。
        jobs[bookUrl]?.cancel()
    }

    private suspend fun runFetch(book: SourceBook) {
        try {
            val source = sourceProvider(applicationContext, book.sourceUrl)
                ?: error("找不到书源（可能已被删除）：${book.sourceUrl}")
            val downloader = downloaderProvider(applicationContext)
            val outcome = downloader.download(source, book) { done, total ->
                BookFetchState.update(
                    BookFetchStatus(book.bookUrl, book.name, BookFetchPhase.RUNNING, done = done, total = total),
                )
                notifyProgress(book, done, total)
            }
            outcome.fold(
                onSuccess = { id ->
                    BookFetchState.update(
                        BookFetchStatus(book.bookUrl, book.name, BookFetchPhase.DONE, bookId = id),
                    )
                },
                onFailure = { error ->
                    BookFetchState.update(
                        BookFetchStatus(
                            book.bookUrl, book.name, BookFetchPhase.FAILED,
                            message = error.message ?: "抓取失败",
                        ),
                    )
                },
            )
        } catch (cancel: CancellationException) {
            BookFetchState.update(BookFetchStatus(book.bookUrl, book.name, BookFetchPhase.CANCELLED))
            throw cancel
        } catch (error: Exception) {
            BookFetchState.update(
                BookFetchStatus(
                    book.bookUrl, book.name, BookFetchPhase.FAILED,
                    message = error.message ?: "抓取失败",
                ),
            )
        } finally {
            jobs.remove(book.bookUrl)
            notifications.cancel(notificationId(book.bookUrl))
            if (jobs.isEmpty()) {
                // 最后一本书收尾：撤掉前台通知并结束服务
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun notifyProgress(book: SourceBook, done: Int, total: Int) {
        notifications.notify(notificationId(book.bookUrl), buildNotification(book, done, total))
    }

    private fun buildNotification(book: SourceBook, done: Int, total: Int): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在抓取《${book.name}》")
            .setContentText(progressText(done, total))
            .setOnlyAlertOnce(true)
            .setOngoing(true)               // 不可划掉
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(book))
            .addAction(0, "取消", cancelActionIntent(book.bookUrl))
        if (total > 0) {
            builder.setProgress(total, done.coerceIn(0, total), false)
        } else {
            builder.setProgress(0, 0, true) // 目录还没抓完：不确定进度
        }
        return builder.build()
    }

    private fun progressText(done: Int, total: Int): String =
        if (total > 0) "第 $done/$total 章" else "准备中…"

    private fun openAppIntent(book: SourceBook): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, pendingFlags())
    }

    private fun cancelActionIntent(bookUrl: String): PendingIntent {
        val intent = Intent(this, BookFetchService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_BOOK_URL, bookUrl)
        return PendingIntent.getService(this, notificationId(bookUrl), intent, pendingFlags())
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "书源整本抓取进度"
            setShowBadge(false)
        }
        notifications.createNotificationChannel(channel)
    }

    companion object {

        private const val ACTION_START = "com.xyreader.booksource.action.FETCH_START"
        private const val ACTION_CANCEL = "com.xyreader.booksource.action.FETCH_CANCEL"
        private const val EXTRA_SOURCE_URL = "sourceUrl"
        private const val EXTRA_SOURCE_NAME = "sourceName"
        private const val EXTRA_BOOK_URL = "bookUrl"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_AUTHOR = "author"

        private const val CHANNEL_ID = "book-fetch"
        private const val CHANNEL_NAME = "书源抓取"

        /**
         * 测试缝：服务跑抓取用的调度器。默认工作线程；单测注入 `Dispatchers.Unconfined` 以便同步断言。
         * 不要在生产代码里改。
         */
        internal var workDispatcher: CoroutineDispatcher = Dispatchers.Default

        /** 测试缝：默认走真实下载器（整本抓取 + 入库） */
        internal var downloaderProvider: (Context) -> LegadoBookDownloader = { LegadoBookDownloader(it) }

        /** 测试缝：默认从本地书源仓库按 URL 取源（与页面用同一个仓库，不额外传参） */
        internal var sourceProvider: suspend (Context, String) -> LegadoBookSource? = { context, url ->
            LegadoSourceStore(context).sources.first().firstOrNull { it.url == url }
        }

        /** 测试缝复位（避免用例之间互相污染） */
        internal fun resetSeams() {
            workDispatcher = Dispatchers.Default
            downloaderProvider = { LegadoBookDownloader(it) }
            sourceProvider = { context, url -> LegadoSourceStore(context).sources.first().firstOrNull { it.url == url } }
        }

        /** 页面上点「抓取整本」：启动前台服务（必须在应用前台调用） */
        fun start(context: Context, source: LegadoBookSource, book: SourceBook) {
            ContextCompat.startForegroundService(context, startIntent(context, source, book))
        }

        /** 页面上点「取消」 */
        fun cancel(context: Context, bookUrl: String) {
            context.startService(cancelIntent(context, bookUrl))
        }

        internal fun startIntent(context: Context, source: LegadoBookSource, book: SourceBook): Intent =
            Intent(context, BookFetchService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SOURCE_URL, source.url)
                putExtra(EXTRA_SOURCE_NAME, source.name)
                putExtra(EXTRA_BOOK_URL, book.bookUrl)
                putExtra(EXTRA_TITLE, book.name)
                putExtra(EXTRA_AUTHOR, book.author)
            }

        internal fun cancelIntent(context: Context, bookUrl: String): Intent =
            Intent(context, BookFetchService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_BOOK_URL, bookUrl)
            }

        private fun notificationId(bookUrl: String): Int = bookUrl.hashCode()
    }
}
