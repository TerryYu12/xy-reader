package com.xyreader.booksource

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 前台服务编排的单元测试（Robolectric，不联网、不碰 Room）。
 *
 * 用假下载器替掉真实抓取：服务只负责编排（进入前台 → 推送进度 → 写状态 → 收尾）。
 * 调度器注入 [Dispatchers.Unconfined]，让协程在调用线程上同步推进，便于断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookFetchServiceTest {

    private enum class Mode { HAPPY, FAIL, BLOCK }

    /** 假下载器：可控地成功/失败/挂起，并记录服务写进状态里的进度 */
    private class FakeDownloader(context: Context, private val mode: Mode) : LegadoBookDownloader(context) {
        /** 每次 onProgress 回调返回后，服务写到 BookFetchState 里的 (done,total) */
        val progressFromState = mutableListOf<Pair<Int, Int>>()

        override suspend fun download(
            source: LegadoBookSource,
            book: SourceBook,
            onProgress: (done: Int, total: Int) -> Unit,
        ): Result<Long> {
            onProgress(1, 3)
            BookFetchState.statuses.value[book.bookUrl]?.let { progressFromState += it.done to it.total }
            return when (mode) {
                Mode.HAPPY -> {
                    onProgress(2, 3)
                    onProgress(3, 3)
                    Result.success(99L)
                }
                Mode.FAIL -> Result.failure(IllegalStateException("第 2/3 章抓取失败"))
                Mode.BLOCK -> awaitCancellation() // 一直挂起，等取消
            }
        }
    }

    private lateinit var context: Application

    private val source = LegadoBookSource.parseImport(
        """{"bookSourceName":"合成书源","bookSourceUrl":"https://novel.example.com","enabled":true}""",
    ).single()

    private val book = SourceBook(
        name = "合成书名",
        bookUrl = "https://novel.example.com/book/1",
        sourceUrl = "https://novel.example.com",
        sourceName = "合成书源",
        author = "合成作者",
    )

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        BookFetchState.reset()
        BookFetchService.workDispatcher = Dispatchers.Unconfined
        BookFetchService.sourceProvider = { _, _ -> source }
    }

    @After fun tearDown() {
        BookFetchService.resetSeams()
        BookFetchState.reset()
    }

    @Test fun startRunsFetchToCompletionAndPublishesProgress() {
        val downloader = FakeDownloader(context, Mode.HAPPY)
        BookFetchService.downloaderProvider = { downloader }
        val controller = Robolectric.buildService(BookFetchService::class.java).create()

        controller.withIntent(BookFetchService.startIntent(context, source, book)).startCommand(0, 1)

        val status = BookFetchState.statuses.value[book.bookUrl]
        assertEquals(BookFetchPhase.DONE, status?.phase)
        assertEquals(99L, status?.bookId)
        assertEquals("服务应把每章进度写进状态", listOf(1 to 3), downloader.progressFromState)
        controller.destroy()
    }

    @Test fun failedFetchIsReportedAsFailedWithoutBookId() {
        BookFetchService.downloaderProvider = { FakeDownloader(context, Mode.FAIL) }
        val controller = Robolectric.buildService(BookFetchService::class.java).create()

        controller.withIntent(BookFetchService.startIntent(context, source, book)).startCommand(0, 1)

        val status = BookFetchState.statuses.value[book.bookUrl]
        assertEquals(BookFetchPhase.FAILED, status?.phase)
        assertNull(status?.bookId)
        assertEquals("第 2/3 章抓取失败", status?.message)
        controller.destroy()
    }

    @Test fun cancelWhileRunningMarksCancelled() {
        BookFetchService.downloaderProvider = { FakeDownloader(context, Mode.BLOCK) }
        val controller = Robolectric.buildService(BookFetchService::class.java).create()

        controller.withIntent(BookFetchService.startIntent(context, source, book)).startCommand(0, 1)
        assertEquals(BookFetchPhase.RUNNING, BookFetchState.statuses.value[book.bookUrl]?.phase)

        controller.withIntent(BookFetchService.cancelIntent(context, book.bookUrl)).startCommand(0, 2)

        assertEquals(BookFetchPhase.CANCELLED, BookFetchState.statuses.value[book.bookUrl]?.phase)
        controller.destroy()
    }

    @Test fun duplicateStartWhileRunningIsIgnored() {
        BookFetchService.downloaderProvider = { FakeDownloader(context, Mode.BLOCK) }
        val controller = Robolectric.buildService(BookFetchService::class.java).create()

        controller.withIntent(BookFetchService.startIntent(context, source, book)).startCommand(0, 1)
        controller.withIntent(BookFetchService.startIntent(context, source, book)).startCommand(0, 2)

        // 第二次不会新建任务，状态仍是 RUNNING（没有被重置/覆盖成完成）
        assertEquals(BookFetchPhase.RUNNING, BookFetchState.statuses.value[book.bookUrl]?.phase)
        controller.withIntent(BookFetchService.cancelIntent(context, book.bookUrl)).startCommand(0, 3)
        assertEquals(BookFetchPhase.CANCELLED, BookFetchState.statuses.value[book.bookUrl]?.phase)
        controller.destroy()
    }
}
