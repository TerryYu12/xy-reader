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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 左右翻页模式 + 图片书（漫画）双击放大 = 整本统一缩放的端到端行为：
 * - 缩放中点按左右分区仍可翻页；
 * - 翻页不复位缩放（0.4.8 及以前翻页即 `pageZoom.reset()`）；
 * - 再双击缩回 1x，拖动翻页恢复。
 *
 * 「当前是否处于缩放态」在测试里读不到 graphicsLayer，改用其可观测副作用钉死：
 * `userScrollEnabled = pageZoom.scale <= 1f` —— 缩放中横向拖动被缩放态接管、不翻页，
 * 缩回 1x 后横滑能翻页。于是「翻页后横滑仍不翻页」即证明缩放未被复位
 * （旧实现在此处缩回 1x，横滑会滑到下一页）。
 *
 * 触点避开右侧中央常驻手势锁钮（36dp、CenterEnd，x 约 318-354）：点按取 75% 宽、
 * 横滑取 80%→10% 宽且 y 取 30%（锁钮在屏幕垂直中央）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MangaUnifiedZoomTest {

    @get:Rule
    val compose = createComposeRule()

    private val pageCount = 4

    /** 推进 [frames] 帧（每帧约 16ms 虚拟时间）：跑完 200ms 双击缩放与翻页动画，不依赖 waitForIdle */
    private fun advance(frames: Int = 80) {
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    /** 第 [index] 页在根坐标里的左边界；未进入组合（离屏）时为 null */
    private fun pageLeft(index: Int): Float? =
        compose.onAllNodesWithContentDescription("第 ${index + 1} 页")
            .fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.left

    /** 第 [index] 页是否停稳在视口起点 */
    private fun atPage(index: Int): Boolean = pageLeft(index)?.let { abs(it) <= 2f } == true

    /** 页面横滑翻页（避开右侧中央手势锁钮） */
    private fun swipePageLeft() {
        compose.onRoot().performTouchInput {
            swipe(
                start = Offset(width * 0.8f, height * 0.3f),
                end = Offset(width * 0.1f, height * 0.3f),
                durationMillis = 200,
            )
        }
    }

    /** 点按右 1/3 分区（避开右侧中央手势锁钮） */
    private fun tapRightThird() {
        compose.onRoot().performTouchInput { click(percentOffset(0.75f, 0.3f)) }
    }

    private fun seedMangaBook(): Long {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "manga-unified-zoom.cbz")
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
                    title = "漫画统一缩放测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 123,
                ),
            )
        }
    }

    @Test
    fun `漫画横向双击放大整本统一缩放且翻页不复位`() {
        val bookId = seedMangaBook()
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) { atPage(0) }

        // 双击 → 整本放大：横滑被缩放态接管（userScrollEnabled=false），不翻页
        compose.onRoot().performTouchInput { doubleClick(center) }
        advance()
        swipePageLeft()
        advance()
        assertFalse("放大后横滑不应翻页", atPage(1))
        assertTrue("放大后应仍停在第 1 页", atPage(0))

        // 缩放中点按右 1/3 分区 → 翻到第 2 页（点按翻页不受缩放影响）
        tapRightThird()
        advance()
        assertTrue("缩放中点按右分区应能翻页", atPage(1))

        // 关键回归：翻页不清零缩放 —— 仍处于缩放态，横滑依旧不翻页
        // （0.4.8 及以前：翻页即复位成 1x，这里会滑到第 3 页）
        swipePageLeft()
        advance()
        assertFalse("翻页后缩放应保持，横滑不应翻页", atPage(2))
        assertTrue("翻页后应停在第 2 页", atPage(1))

        // 再双击 → 整本缩回 1x（offset 清零），拖动翻页恢复
        compose.onRoot().performTouchInput { doubleClick(center) }
        advance()
        swipePageLeft()
        advance()
        assertTrue("缩回 1x 后横滑应恢复翻页", atPage(2))
    }
}
