package com.xyreader.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.data.UpdateInfo
import com.xyreader.data.UpdatePrefsStore
import kotlinx.coroutines.runBlocking
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

        compose.setContent { ArkTheme { ArkNavHost() } }
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
}
