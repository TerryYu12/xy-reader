package com.xyreader.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 主页「开始阅读」菜单行为测试：
 * - 点播放键展开四个选项、再点（关闭键）收起；
 * - 点选项回调对应动作。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuickReadMenuTest {

    @get:Rule
    val compose = createComposeRule()

    private fun visible(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `点播放键展开菜单并可收起`() {
        compose.setContent {
            ArkTheme {
                var open by remember { mutableStateOf(false) }
                QuickReadMenuOverlay(
                    open = open,
                    onToggle = { open = !open },
                    onDismiss = { open = false },
                    onPick = {},
                )
            }
        }

        // 收起态：菜单项未组成
        compose.runOnIdle { assertFalse(visible("上次阅读")) }

        compose.onNodeWithContentDescription("开始阅读").performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            visible("当前书架上次阅读") && visible("上次阅读") &&
                visible("当前书架随机") && visible("随机")
        }

        // 再点（此时按钮为关闭键）→ 菜单收起
        compose.onNodeWithContentDescription("关闭阅读菜单").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { !visible("上次阅读") }
    }

    @Test
    fun `点菜单项回调对应动作`() {
        val picks = mutableListOf<QuickReadKind>()
        compose.setContent {
            ArkTheme {
                QuickReadMenuOverlay(
                    open = true,
                    onToggle = {},
                    onDismiss = {},
                    onPick = { picks += it },
                )
            }
        }
        compose.waitUntil(timeoutMillis = 20_000) { visible("当前书架随机") }
        compose.onNodeWithText("当前书架随机").performClick()
        compose.runOnIdle { assertEquals(listOf(QuickReadKind.SHELF_RANDOM), picks) }
    }
}
