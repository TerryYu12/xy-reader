package com.xyreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.core.BookEntity
import com.xyreader.core.MangaDirection
import com.xyreader.core.PageMode
import com.xyreader.core.ReaderPrefs
import com.xyreader.data.AppGraph
import com.xyreader.data.ArkDatabase
import com.xyreader.ui.ArkTheme
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 手势锁交互流程（0.4.11 起）：
 * - 锁定入口不再常驻右侧中央，而是随顶部菜单一起出现/消失（「点中央 → 菜单出现 → 锁定入口一起出现」）；
 * - 锁定后点屏幕中央呼出解锁钮（满锁图标），点它解锁并呼出菜单；
 * - 防误触语义不变：锁定只拦点击（左右分区静默、双击不缩放），滑动翻页照常。
 *
 * 回归重点（0.4.11 前）：右侧中央常驻锁钮与右 1/3 点按翻页分区重叠——
 * 在旧锁钮位置点按会误入锁定态而不是翻页；本测试钉死该位置必须翻页。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GestureLockFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val pageCount = 4
    private val lockDesc = "锁定手势（防误触）"
    private val unlockDesc = "解锁手势"

    /** 推进 [frames] 帧（每帧约 16ms 虚拟时间）：跑完 250ms 工具栏/锁钮进出场动画，不依赖 waitForIdle */
    private fun advance(frames: Int = 80) {
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun pageLeft(index: Int): Float? =
        compose.onAllNodesWithContentDescription("第 ${index + 1} 页")
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left

    private fun atPage(index: Int): Boolean = pageLeft(index)?.let { abs(it) <= 2f } == true

    private fun lockButtonCount(): Int =
        compose.onAllNodesWithContentDescription(lockDesc).fetchSemanticsNodes().size

    private fun unlockButtonCount(): Int =
        compose.onAllNodesWithContentDescription(unlockDesc).fetchSemanticsNodes().size

    /** 页面横滑翻页 */
    private fun swipePageLeft() {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width * 0.8f, height * 0.3f),
                end = Offset(width * 0.1f, height * 0.3f),
                durationMillis = 200,
            )
        }
    }

    private fun seedMangaBook(): Long {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "gesture-lock-flow.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pageCount) { i ->
                val bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(if (i % 2 == 0) Color.BLUE else Color.GREEN)
                zip.putNextEntry(ZipEntry("${i + 1}.png"))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bitmap.recycle()
            }
        }
        return runBlocking {
            AppGraph.libraryRepository(context).setReaderPrefs(
                ReaderPrefs(
                    pageMode = PageMode.LEFT_RIGHT,
                    mangaDirection = MangaDirection.LTR,
                    tapTurnPage = true,
                    doubleTapZoom = true,
                ),
            )
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(
                    title = "手势锁流程测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 123,
                ),
            )
        }
    }

    @Test
    fun `锁定入口随菜单出现_锁定后点中央呼出解锁钮_旧锁钮位置不再拦截翻页`() {
        val bookId = seedMangaBook()
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) { atPage(0) }

        // 初始菜单隐藏：锁定/解锁入口都不存在（锁定入口不再是常驻按钮）
        assertTrue("初始应无锁定入口（不再常驻）", lockButtonCount() == 0)
        assertTrue("初始应无解锁钮", unlockButtonCount() == 0)

        // 回归：旧锁钮位置（右侧中央 x≈0.93w, y=0.5h）点按必须翻页（0.4.11 前会误入锁定态）
        compose.onRoot().performTouchInput { click(percentOffset(0.93f, 0.5f)) }
        advance()
        assertTrue("右侧中央点按应翻页而不是进入锁定态", atPage(1))

        // 点屏幕中央 → 菜单出现，锁定入口随菜单一起出现
        compose.onRoot().performTouchInput { click(center) }
        advance()
        assertTrue("点中央后菜单与锁定入口应一起出现", lockButtonCount() == 1)

        // 点击锁定入口 → 进入锁定：菜单与锁定入口收起
        compose.onNodeWithContentDescription(lockDesc).performClick()
        advance()
        assertTrue("锁定后菜单应收起（锁定入口消失）", lockButtonCount() == 0)

        // 锁定中：右 1/3 点按静默（不翻页），也不呼出解锁钮
        compose.onRoot().performTouchInput { click(percentOffset(0.75f, 0.3f)) }
        advance()
        assertTrue("锁定中点按右分区不应翻页", atPage(1))
        assertTrue("锁定中点按右分区不应呼出解锁钮", unlockButtonCount() == 0)

        // 锁定中：滑动翻页照常
        swipePageLeft()
        advance()
        assertTrue("锁定中滑动应能翻页", atPage(2))

        // 锁定中点屏幕中央 → 呼出解锁钮
        compose.onRoot().performTouchInput { click(center) }
        advance()
        assertTrue("锁定中点中央应呼出解锁钮", unlockButtonCount() == 1)

        // 点解锁钮 → 解锁并呼出菜单（锁定入口随菜单再次出现）
        compose.onNodeWithContentDescription(unlockDesc).performClick()
        advance()
        assertTrue("解锁后解锁钮应消失", unlockButtonCount() == 0)
        assertTrue("解锁后菜单与锁定入口应随解锁一起出现", lockButtonCount() == 1)
    }
}
