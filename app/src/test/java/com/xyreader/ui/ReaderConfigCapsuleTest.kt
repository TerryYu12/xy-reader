package com.xyreader.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.xyreader.data.AppGraph
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 阅读配置管理页「胶囊分组 ↔ 分页」联动行为测试：
 * - 点顶部胶囊必须切到对应分组（用户报过「左右胶囊切换未实现」，此处以真实 Composable 行为钉死）；
 * - 内容区横滑同样能切组，且与胶囊选中态双向同步。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderConfigCapsuleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun visible(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `点击胶囊切换到对应分组`() {
        val context = RuntimeEnvironment.getApplication()
        AppGraph.libraryRepository(context)
        compose.setContent { ArkTheme { ReaderConfigScreen(onBack = {}) } }

        // 初始在第 1 组「翻页模式」：应当能看到该组的卡片
        compose.waitUntil(timeoutMillis = 20_000) { visible("点击翻页") }

        // 点「字体」胶囊 → 第 3 组内容出现
        compose.onNodeWithText("字体").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { visible("小说字体") }

        // 点「页面」胶囊 → 第 2 组内容出现
        compose.onNodeWithText("页面").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { visible("阅读亮度") }

        // 点回「翻页模式」胶囊
        compose.onNodeWithText("翻页模式").performClick()
        compose.waitUntil(timeoutMillis = 20_000) { visible("点击翻页") }
    }

    @Test
    fun `内容区横滑切换分组`() {
        val context = RuntimeEnvironment.getApplication()
        AppGraph.libraryRepository(context)
        compose.setContent { ArkTheme { ReaderConfigScreen(onBack = {}) } }

        compose.waitUntil(timeoutMillis = 20_000) { visible("漫画方向") }

        // 在内容区横向左滑 → 第 2 组「页面」（在 root 全宽上滑，避免节点局部距离过短回弹）
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitUntil(timeoutMillis = 20_000) { visible("阅读亮度") }

        // 横向右滑 → 回第 1 组
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitUntil(timeoutMillis = 20_000) { visible("漫画方向") }
    }
}
