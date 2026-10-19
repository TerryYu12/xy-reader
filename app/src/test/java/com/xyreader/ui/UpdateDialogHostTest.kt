package com.xyreader.ui

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.data.UpdateInfo
import com.xyreader.data.UpdatePrefsStore
import com.xyreader.data.UpdateDownloadSnapshot
import com.xyreader.data.UpdateDownloadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateDialogHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val updateInfo = UpdateInfo(
        version = "0.5.1",
        tag = "v0.5.1",
        notes = "更新说明",
        assetUrl = "https://example.com/XY-READER-0.5.1.apk",
        assetSize = 123L,
        htmlUrl = "https://github.com/TerryYu12/xy-reader/releases/tag/v0.5.1",
    )

    @After
    fun resetUpdateHost() {
        UpdateHostState.dismiss()
    }

    @Test
    fun manualUpdateStateRendersOnSettingsDestination() {
        val context = RuntimeEnvironment.getApplication()
        runBlocking { UpdatePrefsStore(context).markChecked(System.currentTimeMillis()) }
        UpdateHostState.dismiss()

        compose.setContent { ArkTheme { ArkNavHost(automaticUpdateCheck = { Result.success(null) }) } }
        compose.onNodeWithContentDescription("设置").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("版本").fetchSemanticsNodes().isNotEmpty()
        }

        UpdateHostState.show(updateInfo, currentVersion = "0.5.0")
        compose.waitForIdle()
        compose.onNodeWithText("发现新版本 v0.5.1").assertIsDisplayed()
        compose.onNodeWithText("版本").assertExists()
    }

    @Test
    fun releasePageRemainsAvailableWhenReleaseHasNoApk() {
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    info = updateInfo.copy(assetUrl = null, assetSize = 0L),
                    currentVersion = "0.5.0",
                    onDismiss = {},
                )
            }
        }

        compose.onNodeWithText("浏览器打开").assertIsDisplayed()
        compose.onNodeWithText("立即更新").assertDoesNotExist()
    }

    @Test
    fun eachForegroundCheckRequestsAgainEvenAfterSuccessOrFailure() = runBlocking {
        var calls = 0
        var shown = 0
        val check: suspend (String) -> Result<UpdateInfo?> = {
            calls++
            if (calls == 2) Result.failure(java.io.IOException("offline"))
            else Result.success(updateInfo)
        }
        repeat(3) { runAutomaticCheck("0.5.0", check) { shown++ } }
        assertEquals(3, calls)
        assertEquals(2, shown)
    }

    @Test
    fun downloadingCanBeDismissedToBackground() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    info = updateInfo,
                    currentVersion = "0.5.0",
                    onDismiss = { dismissed = true },
                    download = UpdateDownloadSnapshot(updateInfo, UpdateDownloadStatus.DOWNLOADING, 42),
                )
            }
        }
        compose.onNodeWithText("下载中… 42%").assertIsDisplayed()
        compose.onNodeWithText("后台下载").assertIsEnabled().performClick()
        assertEquals(true, dismissed)
    }

    @Test
    fun readyDownloadWaitsForExplicitInstall() {
        val application = RuntimeEnvironment.getApplication()
        val shadow = org.robolectric.Shadows.shadowOf(application)
        shadow.clearNextStartedActivities()
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    info = updateInfo,
                    currentVersion = "0.5.0",
                    onDismiss = {},
                    download = UpdateDownloadSnapshot(
                        updateInfo, UpdateDownloadStatus.READY, 100,
                        file = java.io.File(application.cacheDir, "update/test.apk"),
                    ),
                )
            }
        }
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        assertEquals(null, shadow.nextStartedActivity)
    }

    @Test
    fun automaticCheckRunsAgainWhenAppReturnsFromBackground() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val calls = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme {
                    UpdateHost(check = { calls.incrementAndGet(); Result.success(null) })
                }
            }
        }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(10_000) { calls.get() == 1 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitForIdle()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.waitUntil(10_000) { calls.get() == 2 }
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.DESTROYED }
        assertEquals(2, calls.get())
    }

    @Test
    fun readyDownloadCanBeDeferredAndReopenedWithoutDownloadingAgain() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        org.robolectric.shadows.ShadowDownloadManager.reset()
        val manager = com.xyreader.data.UpdateDownloadManager(context)
        val future = updateInfo.copy(version = "9.0.0", tag = "v9.0.0")
        manager.enqueue(future)
        val system = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
        val shadow = org.robolectric.Shadows.shadowOf(system)
        val request = org.robolectric.Shadows.shadowOf(shadow.getRequest(0L))
        java.io.File(request.destination.path!!).writeBytes(ByteArray(future.assetSize.toInt()))
        request.setStatus(android.app.DownloadManager.STATUS_SUCCESSFUL)
        request.setTotalSize(future.assetSize)
        compose.setContent {
            MaterialTheme { UpdateHost(check = { Result.success(null) }) }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("安装更新 v9.0.0").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("安装更新 v9.0.0").performClick()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        compose.onNodeWithText("稍后再说").performClick()
        compose.onNodeWithText("安装更新 v9.0.0").assertIsDisplayed().performClick()
        compose.onNodeWithText("安装更新").assertIsDisplayed()
        assertEquals(1, shadow.requestCount)
    }

    @Test
    fun failedDownloadOffersRetryAndBrowserFallback() {
        var retried = false
        compose.setContent {
            MaterialTheme {
                UpdateDialog(
                    info = updateInfo,
                    currentVersion = "0.5.0",
                    onDismiss = {},
                    download = UpdateDownloadSnapshot(
                        updateInfo, UpdateDownloadStatus.FAILED, -1, error = "下载失败：网络错误",
                    ),
                    onDownload = { retried = true },
                )
            }
        }
        assertFalse(retried)
        compose.onNodeWithText("浏览器打开").assertIsDisplayed()
        compose.onNodeWithText("重试下载").performClick()
        assertEquals(true, retried)
    }
}
