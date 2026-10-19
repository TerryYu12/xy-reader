package com.xyreader.data

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.xyreader.core.Acquisition
import com.xyreader.core.DownloadEntry
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDownloadManager

/**
 * 书籍下载基础设施：身份/命名、任务持久化、进度、内容校验与「先落盘后入库」。
 * 用 Robolectric 的系统 DownloadManager shadow 驱动，不真联网；入库用假 importer 记录调用。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookDownloadManagerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val systemDownloads: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val shadow get() = shadowOf(systemDownloads)

    private val entry = DownloadEntry(
        sourceId = "opds-home",
        itemId = "book-42",
        title = "测试书",
        format = "EPUB",
    )
    private val acquisition = Acquisition.DirectFile(
        url = "https://library.example.com/books/42.epub",
        sizeBytes = 4L,
    )

    /** 记录入库调用（真实现要碰 Room 与私有目录，这里只验证契约参数） */
    private class FakeImporter : DownloadedBookImporter {
        val calls = mutableListOf<Triple<File, String, String>>()
        var nextBookId = 7L
        override suspend fun import(file: File, storedName: String, title: String): Long {
            calls += Triple(file, storedName, title)
            return nextBookId
        }
    }

    @Before fun resetState() {
        ShadowDownloadManager.reset()
        context.getSharedPreferences("book_download", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** 系统任务的暂存落点（外部下载目录下的 book-downloads/） */
    private fun stagedFileOf(index: Long): File =
        File(shadowOf(shadow.getRequest(index)).destination.path!!).also { it.parentFile?.mkdirs() }

    /** 把系统任务推到「传输完成」，并按给定字节写暂存文件 */
    private fun completeWith(index: Long, bytes: ByteArray): File {
        val staged = stagedFileOf(index)
        staged.writeBytes(bytes)
        shadowOf(shadow.getRequest(index)).apply {
            setStatus(DownloadManager.STATUS_SUCCESSFUL)
            setTotalSize(bytes.size.toLong())
            setBytesSoFar(bytes.size.toLong())
        }
        return staged
    }

    private fun markRunning(index: Long, total: Long, soFar: Long) {
        shadowOf(shadow.getRequest(index)).apply {
            setStatus(DownloadManager.STATUS_RUNNING)
            setTotalSize(total)
            setBytesSoFar(soFar)
        }
    }

    private val pdfBytes = "%PDF-1.7 fake book content".toByteArray()

    @Test fun taskIsSystemManagedNamedByIdentityAndDeduplicated() = runBlocking {
        val manager = BookDownloadManager(context, FakeImporter())
        manager.enqueue(entry, acquisition)
        assertEquals(1, shadow.requestCount)

        val request = shadowOf(shadow.getRequest(0L))
        assertEquals("application/epub+zip", request.mimeType)
        assertEquals("测试书", request.title)
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request.notificationVisibility)
        assertEquals(
            File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "book-downloads").canonicalFile,
            File(request.destination.path!!).parentFile!!.canonicalFile,
        )

        markRunning(0L, 8L, 4L)
        val recovered = BookDownloadManager(context, FakeImporter())
        assertEquals(entry, recovered.snapshot()!!.entry)
        assertEquals(BookDownloadStatus.DOWNLOADING, recovered.snapshot()!!.status)
        assertEquals(50, recovered.snapshot()!!.progress)

        // 同一身份重复提交不新建任务；换来源（标题相同）则新建，且落盘名不同
        recovered.enqueue(entry, acquisition)
        assertEquals(1, shadow.requestCount)
        recovered.enqueue(entry.copy(sourceId = "calibre-home"), acquisition)
        assertEquals(2, shadow.requestCount)
        assertTrue(
            shadowOf(shadow.getRequest(0L)).destination.path !=
                shadowOf(shadow.getRequest(1L)).destination.path,
        )
    }

    @Test fun completedDownloadIsImportedWithIdentityNameAndDisplayTitle() = runBlocking {
        val importer = FakeImporter()
        val manager = BookDownloadManager(context, importer)
        val pdfEntry = entry.copy(format = "PDF")
        manager.enqueue(
            pdfEntry,
            acquisition.copy(url = "https://library.example.com/books/42.pdf", sizeBytes = pdfBytes.size.toLong()),
        )
        val staged = completeWith(0L, pdfBytes)

        val snapshot = manager.snapshot()!!
        assertEquals(BookDownloadStatus.IMPORTED, snapshot.status)
        assertEquals(100, snapshot.progress)
        assertEquals(7L, snapshot.bookId)

        val (file, storedName, title) = importer.calls.single()
        assertEquals(staged.canonicalFile, file.canonicalFile)
        assertEquals("测试书", title)
        assertTrue(storedName.startsWith("opds-home_book-42_"))
        assertTrue(storedName.endsWith(".pdf"))

        // 入库后即使系统任务被清理，状态仍是已入库（不需要重新下载）
        ShadowDownloadManager.reset()
        val restored = BookDownloadManager(context, importer).snapshot()!!
        assertEquals(BookDownloadStatus.IMPORTED, restored.status)
        assertEquals(7L, restored.bookId)
        assertEquals(1, importer.calls.size)
    }

    @Test fun htmlLoginPagePretendingToBeEpubIsRejected() = runBlocking {
        val importer = FakeImporter()
        val manager = BookDownloadManager(context, importer)
        manager.enqueue(entry, acquisition.copy(sizeBytes = null))
        val staged = completeWith(0L, "<html><body>请先登录</body></html>".toByteArray())

        val snapshot = manager.snapshot()!!
        assertEquals(BookDownloadStatus.FAILED, snapshot.status)
        assertTrue(snapshot.error!!.contains("网页"))
        assertTrue(importer.calls.isEmpty())
        assertTrue(staged.exists())
    }

    @Test fun contentContradictingDeclaredFormatIsRejected() = runBlocking {
        val importer = FakeImporter()
        val manager = BookDownloadManager(context, importer)
        manager.enqueue(entry, acquisition.copy(sizeBytes = pdfBytes.size.toLong()))
        completeWith(0L, pdfBytes)

        val snapshot = manager.snapshot()!!
        assertEquals(BookDownloadStatus.FAILED, snapshot.status)
        assertTrue(snapshot.error!!.contains("格式不符"))
        assertTrue(importer.calls.isEmpty())
    }

    @Test fun truncatedDownloadNeverImports() = runBlocking {
        val importer = FakeImporter()
        val manager = BookDownloadManager(context, importer)
        manager.enqueue(entry, acquisition.copy(sizeBytes = 64L))
        completeWith(0L, byteArrayOf(0x50, 0x4B, 3, 4))

        val snapshot = manager.snapshot()!!
        assertEquals(BookDownloadStatus.FAILED, snapshot.status)
        assertTrue(snapshot.error!!.contains("大小"))
        assertTrue(importer.calls.isEmpty())
    }

    @Test fun missingStagedFileAndMissingSystemTaskBothFail() = runBlocking {
        val manager = BookDownloadManager(context, FakeImporter())
        manager.enqueue(entry, acquisition)
        shadowOf(shadow.getRequest(0L)).apply {
            setStatus(DownloadManager.STATUS_SUCCESSFUL)
            setTotalSize(4L)
        }
        assertTrue(manager.snapshot()!!.error!!.contains("丢失"))

        ShadowDownloadManager.reset()
        val afterCleanup = manager.snapshot()!!
        assertEquals(BookDownloadStatus.FAILED, afterCleanup.status)
        assertTrue(afterCleanup.error!!.contains("不存在"))
    }

    @Test fun failedTaskCanBeRetriedWithoutTouchingPreviousTask() = runBlocking {
        val manager = BookDownloadManager(context, FakeImporter())
        manager.enqueue(entry, acquisition)
        val first = stagedFileOf(0L)
        first.writeBytes(byteArrayOf(1, 2, 3))
        shadowOf(shadow.getRequest(0L)).setStatus(DownloadManager.STATUS_FAILED)
        assertTrue(manager.snapshot()!!.error!!.contains("失败"))

        manager.enqueue(entry, acquisition)
        assertEquals(2, shadow.requestCount)
        // 同一身份重试复用同一落盘名（幂等），不产生重复暂存文件；旧文件不被删除
        assertEquals(
            shadowOf(shadow.getRequest(0L)).destination.path,
            shadowOf(shadow.getRequest(1L)).destination.path,
        )
        assertTrue(first.exists())
    }

    @Test fun explicitRetryRecoversMalformedRecord() = runBlocking {
        context.getSharedPreferences("book_download", Context.MODE_PRIVATE)
            .edit().putString("task", "{invalid JSON").commit()
        val manager = BookDownloadManager(context, FakeImporter())
        assertTrue(runCatching { manager.snapshot() }.isFailure)

        manager.enqueue(entry, acquisition)
        assertEquals(1, shadow.requestCount)
        // Robolectric 的 shadow 默认状态是 0；显式置为系统真实的 PENDING 才能读出「下载中」
        shadowOf(shadow.getRequest(0L)).setStatus(DownloadManager.STATUS_PENDING)
        val restored = BookDownloadManager(context, FakeImporter()).snapshot()!!
        assertEquals(entry, restored.entry)
        assertEquals(BookDownloadStatus.DOWNLOADING, restored.status)
    }

    @Test fun forgetOnlyDropsAppReference() = runBlocking {
        val manager = BookDownloadManager(context, FakeImporter())
        manager.enqueue(entry, acquisition)
        manager.forget()
        assertEquals(null, BookDownloadManager(context, FakeImporter()).snapshot())
        assertEquals(1, shadow.requestCount)
    }

    @Test fun publicPlainHttpAndUnknownFormatAreRejected() = runBlocking {
        val manager = BookDownloadManager(context, FakeImporter())
        val overPlainHttp = runCatching {
            manager.enqueue(entry, acquisition.copy(url = "http://library.example.com/42.epub"))
        }.exceptionOrNull()
        assertTrue(overPlainHttp is IOException)
        assertEquals(0, shadow.requestCount)

        val noFormat = runCatching {
            manager.enqueue(
                entry.copy(format = null),
                acquisition.copy(url = "https://library.example.com/download?id=42"),
            )
        }.exceptionOrNull()
        assertTrue(noFormat is IOException)

        // 局域网/回环地址允许明文 HTTP（自建目录常见形态）
        manager.enqueue(entry, acquisition.copy(url = "http://192.168.1.20:8080/books/42.epub"))
        assertEquals(1, shadow.requestCount)
        assertTrue(isAllowedDownloadUri(Uri.parse("http://127.0.0.1:8080/a.epub")))
        assertTrue(isAllowedDownloadUri(Uri.parse("http://172.16.5.5/a.epub")))
        assertFalse(isAllowedDownloadUri(Uri.parse("http://8.8.8.8/a.epub")))
        assertFalse(isAllowedDownloadUri(Uri.parse("ftp://192.168.1.20/a.epub")))
        assertFalse(isAllowedDownloadUri(Uri.parse("https:///no-host.epub")))
    }

    @Test fun storedNameIsDeterministicAndCollisionProof() {
        val base = BookDownloadNaming.storedFileName(entry, "epub")
        // 标题不参与命名：换书名不影响落盘名（同一身份重复下载幂等）
        assertEquals(base, BookDownloadNaming.storedFileName(entry.copy(title = "完全不同的书名"), "epub"))
        // 换来源 / 换版本 → 不同落盘名（跨来源同名、同条目多版本都不互相覆盖）
        assertFalse(base == BookDownloadNaming.storedFileName(entry.copy(sourceId = "calibre-home"), "epub"))
        assertFalse(base == BookDownloadNaming.storedFileName(entry.copy(version = "v2"), "epub"))

        val messy = BookDownloadNaming.storedFileName(
            entry.copy(sourceId = "a/b\\c", itemId = "   ", version = "卷:一".repeat(30)),
            "cbz",
        )
        assertTrue(messy.endsWith(".cbz"))
        assertFalse(messy.contains("/"))
        assertTrue(messy.length <= 100)
    }
}
