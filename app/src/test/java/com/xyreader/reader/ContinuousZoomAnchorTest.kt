package com.xyreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.xyreader.core.BookEntity
import com.xyreader.core.PageMode
import com.xyreader.core.ReaderPrefs
import com.xyreader.data.AppGraph
import com.xyreader.data.ArkDatabase
import com.xyreader.ui.ArkTheme
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 上下连续模式双击放大的**动画期间逐帧锚定**：双击点处的文档点在 1x→2.5x 的整段动画里
 * 必须停在原地（不漂移），动画结束也不许瞬跳（0.4.8 及以前：逐帧只改 scale、结束时才
 * 一次性 requestScrollToItem，动画中内容持续漂移、结尾瞬跳）。
 *
 * 观测方式：整列缩放只改变页面布局高度与滚动偏移，二者都能从页面语义节点的 boundsInRoot
 * 反推 —— 页高 = 240 × scale（页面 600×400、视口 360dp），故
 *   滚动偏移 = 240 × 首个可见页下标 × scale − 该页屏幕 top
 *   焦点文档点 = (滚动偏移 + 焦点 y) / scale
 * 逐帧采样这个值即可还原「动画期间焦点是否漂移」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContinuousZoomAnchorTest {

    @get:Rule
    val compose = createComposeRule()

    private val pageCount = 4

    /** scale=1 时每页高度：视口宽 360px ÷ 宽高比 1.5 */
    private val baseHeight = 240f

    /** 双击/焦点位置（屏幕 y）：落在第 2 页中段（坐标原为避开右中常驻锁钮而取，0.4.11 起锁钮已移入顶部菜单） */
    private val focalY = 360f

    private fun pageBounds(): List<Pair<Int, Rect>> =
        (0 until pageCount).mapNotNull { i ->
            compose.onAllNodesWithContentDescription("第 ${i + 1} 页").fetchSemanticsNodes()
                .firstOrNull()?.let { i to it.boundsInRoot }
        }

    /**
     * 视口内完整可见的页：boundsInRoot 是**裁剪后**的边界，贴边的页会伪装成"高度较小"，
     * 故要求上下都离视口边至少 1px。整列缩放后页高 ≤ 600px < 视口 800px，必有一页落在中间。
     */
    private fun unclippedPage(): Pair<Int, Rect>? {
        val h = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        return pageBounds().firstOrNull { (_, b) -> b.top >= 1f && b.bottom <= h - 1f }
    }

    /** 由完整可见页的布局高度反推当前整列缩放倍数 */
    private fun currentScale(): Float = unclippedPage()!!.second.height / baseHeight

    /** 屏幕上 [focalY] 处文档点在 scale=1 文档坐标系里的位置 */
    private fun docAtFocal(): Float {
        val (index, bounds) = unclippedPage()!!
        val scale = bounds.height / baseHeight
        val scroll = baseHeight * index * scale - bounds.top
        return (scroll + focalY) / scale
    }

    private fun seedBook(): Long {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "continuous-anchor.cbz")
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
                ReaderPrefs(pageMode = PageMode.UP_DOWN, doubleTapZoom = true),
            )
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(
                    title = "连续缩放锚定测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 321,
                ),
            )
        }
    }

    @Test
    fun `双击放大动画全程锚定焦点文档点`() {
        val bookId = seedBook()
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) { pageBounds().size >= 2 }

        assertEquals("双击前应为 1x", 1f, currentScale(), 0.01f)
        val docBefore = docAtFocal()

        // 手动时钟：双击动画不自动跑完，才能逐帧采样整段 200ms
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { click(Offset(180f, focalY)) }
        compose.mainClock.advanceTimeBy(60) // 双击窗口（300ms）内送出第二下
        compose.onRoot().performTouchInput { click(Offset(180f, focalY)) }

        // 逐帧采样：动画期间每一帧焦点文档点都应停在双击处
        val scales = mutableListOf<Float>()
        repeat(40) {
            compose.mainClock.advanceTimeByFrame()
            val scale = currentScale()
            scales += scale
            assertEquals(
                "scale=$scale 时焦点文档点漂移（动画期间应逐帧锚定）",
                docBefore,
                docAtFocal(),
                4f,
            )
        }
        compose.mainClock.autoAdvance = true

        // 自证：确实采到了动画中间帧（否则断言可能因"没采到动画"而空过）
        assertTrue("应采样到动画中间帧，实际采样=$scales", scales.any { it > 1.2f && it < 2.4f })

        // 动画跑完到 2.5 倍，且结束帧仍锚定在双击处（无结尾瞬跳）
        assertEquals("动画应放大到 2.5x", 2.5f, scales.max(), 0.01f)
        assertEquals("结束帧焦点文档点应仍在双击处", docBefore, docAtFocal(), 4f)
        assertTrue("整列缩放应撑高页面（页页相接）", currentScale() > 1.5f)
    }
}
