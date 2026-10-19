package com.xyreader.data

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDownloadManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDownloadManagerTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val systemDownloads: DownloadManager
        get() = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val shadow get() = shadowOf(systemDownloads)
    private val info = UpdateInfo(
        version = "0.6.0", tag = "v0.6.0", notes = "更新说明",
        assetUrl = "https://github.com/TerryYu12/xy-reader/releases/download/v0.6.0/reader.apk",
        assetSize = 4L,
        htmlUrl = "https://github.com/TerryYu12/xy-reader/releases/tag/v0.6.0",
    )

    @Before fun resetState() {
        ShadowDownloadManager.reset()
        context.getSharedPreferences("update_download", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun requestSurvivesManagerRecreationAndCannotBeDuplicated() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        request.setStatus(DownloadManager.STATUS_RUNNING)
        request.setTotalSize(4L)
        request.setBytesSoFar(2L)

        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED, request.notificationVisibility)
        assertEquals("application/vnd.android.package-archive", request.mimeType)
        assertEquals(
            File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "update").canonicalFile,
            File(request.destination.path!!).parentFile!!.canonicalFile,
        )
        val recovered = UpdateDownloadManager(context)
        assertEquals(info, recovered.snapshot()!!.info)
        assertEquals(50, recovered.snapshot()!!.progress)
        recovered.enqueue(info)
        recovered.enqueue(info.copy(version = "0.6.1", tag = "v0.6.1"))
        assertEquals(1, shadow.requestCount)
    }

    @Test fun unknownSizeAndPausedDownloadRemainResumable() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        request.setStatus(DownloadManager.STATUS_PAUSED)
        request.setTotalSize(-1L)
        assertEquals(UpdateDownloadStatus.DOWNLOADING, manager.snapshot()!!.status)
        assertEquals(-1, manager.snapshot()!!.progress)
    }

    @Test fun completedDownloadIsValidatedAndCopiedIntoFileProviderCache() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        val source = File(request.destination.path!!)
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        request.setTotalSize(4L)
        request.setBytesSoFar(4L)

        val state = manager.snapshot()!!
        assertEquals(UpdateDownloadStatus.READY, state.status)
        assertEquals(100, state.progress)
        val file = state.file!!
        assertEquals(File(context.cacheDir, "update").canonicalFile, file.parentFile!!.canonicalFile)
        assertTrue(file.readBytes().contentEquals(source.readBytes()))
        assertEquals(file, UpdateDownloadManager(context).snapshot()!!.file)
        manager.enqueue(info.copy(notes = "更新后的说明"))
        assertEquals(1, shadow.requestCount)
        assertNull(shadowOf(context).nextStartedActivity)
    }

    @Test fun truncatedApkNeverBecomesReady() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        File(request.destination.path!!).writeBytes(byteArrayOf(1, 2))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        request.setTotalSize(4L)
        val state = manager.snapshot()!!
        assertEquals(UpdateDownloadStatus.FAILED, state.status)
        assertNull(state.file)
        assertTrue(state.error!!.contains("大小"))
    }

    @Test fun missingTaskAndMissingDownloadedFileReportFailure() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        assertEquals(UpdateDownloadStatus.FAILED, manager.snapshot()!!.status)
        assertTrue(manager.snapshot()!!.error!!.contains("丢失"))
        // 模拟系统清理任务，不调用应用的任务删除逻辑。
        ShadowDownloadManager.reset()
        assertEquals(UpdateDownloadStatus.FAILED, manager.snapshot()!!.status)
        assertTrue(manager.snapshot()!!.error!!.contains("不存在"))
    }

    @Test fun failedSystemTaskCanBeRetriedWithoutRemovingPreviousTask() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        shadowOf(shadow.getRequest(0L)).setStatus(DownloadManager.STATUS_FAILED)
        assertEquals(UpdateDownloadStatus.FAILED, manager.snapshot()!!.status)
        assertTrue(manager.snapshot()!!.error!!.contains("失败"))
        manager.enqueue(info)
        assertEquals(2, shadow.requestCount)
        val firstPath = shadowOf(shadow.getRequest(0L)).destination
        val secondPath = shadowOf(shadow.getRequest(1L)).destination
        assertTrue(firstPath != secondPath)
    }

    @Test fun forgettingOnlyClearsAppReferenceAndRetainsSystemTaskAndFiles() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val request = shadowOf(shadow.getRequest(0L))
        val source = File(request.destination.path!!)
        source.writeBytes(byteArrayOf(1, 2, 3, 4))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        request.setTotalSize(4L)
        val cached = manager.snapshot()!!.file!!
        manager.forget()
        assertNull(UpdateDownloadManager(context).snapshot())
        assertEquals(1, shadow.requestCount)
        assertTrue(source.exists())
        assertTrue(cached.exists())
    }

    @Test fun noRecordReturnsNullAndPlainHttpIsRejected() = runBlocking {
        val manager = UpdateDownloadManager(context)
        assertNull(manager.snapshot())
        val failure = runCatching { manager.enqueue(info.copy(assetUrl = "http://example.com/reader.apk")) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, shadow.requestCount)
    }

    @Test fun explicitRetryRecoversMalformedRecordWithoutDeletingOldSystemTask() = runBlocking {
        val manager = UpdateDownloadManager(context)
        manager.enqueue(info)
        val oldSource = File(shadowOf(shadow.getRequest(0L)).destination.path!!)
        oldSource.writeBytes(byteArrayOf(1, 2, 3, 4))
        context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
            .edit().putString("task", "{invalid JSON").commit()
        assertTrue(runCatching { manager.snapshot() }.isFailure)

        manager.enqueue(info)
        shadowOf(shadow.getRequest(1L)).setStatus(DownloadManager.STATUS_RUNNING)
        val restored = UpdateDownloadManager(context).snapshot()!!
        assertEquals(info, restored.info)
        assertEquals(UpdateDownloadStatus.DOWNLOADING, restored.status)
        assertEquals(2, shadow.requestCount)
        assertTrue(oldSource.exists())
        assertEquals(4L, oldSource.length())
    }

    @Test fun explicitRetryRecoversRecordStoredWithWrongPreferenceType() = runBlocking {
        context.getSharedPreferences("update_download", Context.MODE_PRIVATE)
            .edit().putLong("task", 123L).commit()
        val manager = UpdateDownloadManager(context)
        assertTrue(runCatching { manager.snapshot() }.isFailure)
        manager.enqueue(info)
        shadowOf(shadow.getRequest(0L)).setStatus(DownloadManager.STATUS_PENDING)
        assertEquals(info, manager.snapshot()!!.info)
        assertEquals(UpdateDownloadStatus.DOWNLOADING, manager.snapshot()!!.status)
        assertEquals(1, shadow.requestCount)
    }
}
