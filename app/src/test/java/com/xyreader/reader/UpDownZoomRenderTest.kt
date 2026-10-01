package com.xyreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * 上下滚动模式（PageMode.UP_DOWN）缩放的**像素级**回归：双击/捏合必须真正放大页面内容，
 * 而不是只把 LazyColumn 的项高撑大（0.4.9 的缺陷：框变高、图不变，页面线距缩放前后恒为 30px）。
 *
 * 为什么必须读像素：旧测试只断言布局高度与滚动偏移（`boundsInRoot` 反推），
 * 「图没放大」在那种口径下完全隐形——本用例专治这个盲区。
 *
 * 合成页：600×400 白底 + y=175..225 的黑色整宽横条（marker）。
 * 页面按视口宽 360px 适配 → 1x 时 marker 高 30px，2.5x 时应为 75px。
 * 度量列取 x=180（屏幕中线；早期为避开右中常驻锁钮而取中线，0.4.11 起锁钮已移入顶部菜单）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpDownZoomRenderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val pageCount = 4
    private val pageW = 600
    private val pageH = 400

    /** marker 在页面位图里的纵向范围 */
    private val markerTopPx = 175
    private val markerBottomPx = 225

    /** 视口宽 360px ÷ 宽高比 1.5 = 1x 时页高 */
    private val baseHeight = 240f

    /** 1x 时 marker 在屏幕上的高度（px）：(225-175) × 360/600 */
    private val markerAt1x = 30

    /** 度量列：屏幕中线 */
    private val scanX = 180

    /** 推进 [frames] 帧（每帧约 16ms）：跑完 200ms 双击缩放动画 */
    private fun advance(frames: Int = 80) {
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun pageBounds(): List<Pair<Int, Rect>> =
        (0 until pageCount).mapNotNull { i ->
            compose.onAllNodesWithContentDescription("第 ${i + 1} 页").fetchSemanticsNodes()
                .firstOrNull()?.let { i to it.boundsInRoot }
        }

    /** 视口内完整可见的页（boundsInRoot 是裁剪后边界，贴边的页不可用于反推高度） */
    private fun unclippedPage(): Pair<Int, Rect>? {
        val h = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        return pageBounds().firstOrNull { (_, b) -> b.top >= 1f && b.bottom <= h - 1f }
    }

    /**
     * 屏幕截图（真读像素）。
     *
     * 不用 `captureToImage()`：它在 `forceRedraw` 里等 ViewTreeObserver 的 OnDraw 回调
     * （`WindowCapture.android.kt:124`），Robolectric 不跑 ViewRootImpl 的绘制遍历，
     * 必然 `ComposeTimeoutException: Condition still not satisfied after 2000 ms`。
     * 改为把内容视图软件光栅到一个 Bitmap——Robolectric NATIVE 下 Skia 真实绘制，
     * graphicsLayer 的 translate/scale 也会被 `concat(matrix)` 应用（已实测）。
     */
    private fun screenshot(): Bitmap {
        val view = compose.activity.findViewById<ViewGroup>(android.R.id.content)
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        return bmp
    }

    /**
     * marker 像素判定：用**颜色**而不是明暗。页面是白底、阅读器背景是深色，
     * 两种都是「明暗」可混淆的（只撑框不放大图时页面外露出的深色背景会冒充暗段），
     * 而 marker 是唯一的红色，判据不会误伤。
     */
    private fun isMarker(p: Int): Boolean =
        Color.red(p) > 120 && Color.green(p) < 80 && Color.blue(p) < 80

    /**
     * 在 [scanX] 列上找「完整可见的连续 marker 段」，返回首个的 (上沿, 下沿)。
     * 贴屏幕上下边的段一并排除（可能被裁掉一半，量出的厚度偏小）。
     */
    private fun markerRun(): Pair<Int, Int> {
        val bmp = screenshot()
        val h = bmp.height
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in 0 until h) {
            if (isMarker(bmp.getPixel(scanX, y))) {
                if (start < 0) start = y
            } else if (start >= 0) {
                runs += start to y - 1
                start = -1
            }
        }
        if (start >= 0) runs += start to h - 1
        return runs.firstOrNull { it.first > 0 && it.second < h - 1 && it.second - it.first + 1 >= 8 }
            ?: throw AssertionError(
                "截图 ${bmp.width}x$h 的第 $scanX 列未找到完整可见的 marker 段；全部段=$runs",
            )
    }

    /** 全部 marker 段（含贴边的）——用于诊断 */
    private fun allRuns(): List<Pair<Int, Int>> {
        val bmp = screenshot()
        val h = bmp.height
        val runs = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (y in 0 until h) {
            if (isMarker(bmp.getPixel(scanX, y))) {
                if (start < 0) start = y
            } else if (start >= 0) {
                runs += start to y - 1
                start = -1
            }
        }
        if (start >= 0) runs += start to h - 1
        return runs
    }

    /** marker 在屏幕上的可见高度（px） */
    private fun markerHeight(): Int = markerRun().let { it.second - it.first + 1 }

    /** marker 上沿的屏幕 y */
    private fun markerTopOnScreen(): Int = markerRun().first

    /** 每页 600×400 白底 + 整宽红色横条（y=175..225） */
    private fun makePage(): Bitmap {
        val bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(
            0f,
            markerTopPx.toFloat(),
            pageW.toFloat(),
            markerBottomPx.toFloat(),
            Paint().apply { color = Color.RED },
        )
        return bmp
    }

    private fun seedBook(name: String): Long {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            repeat(pageCount) { i ->
                val bmp = makePage()
                zip.putNextEntry(ZipEntry("${i + 1}.png"))
                bmp.compress(Bitmap.CompressFormat.PNG, 100, zip)
                zip.closeEntry()
                bmp.recycle()
            }
        }
        return runBlocking {
            AppGraph.libraryRepository(context).setReaderPrefs(
                ReaderPrefs(pageMode = PageMode.UP_DOWN, doubleTapZoom = true),
            )
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(
                    title = "上下缩放像素测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 432,
                ),
            )
        }
    }

    /**
     * 双击路径：1x 截图量 marker 高 h0 → 双击（双击点 y=50，落在第 1 页上段）→ 动画跑完再量 h1。
     * 断言 h1/h0 ≈ 2.5（±0.3），且双击点处的文档内容按缩放倍数平移（像素级锚定，±10px）。
     */
    @Test
    fun `上下模式双击后页面内容真的放大 2_5 倍`() {
        val bookId = seedBook("updown-render-doubletap.cbz")
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) { pageBounds().size >= 2 }

        val focalY = 50f
        val h0 = markerHeight()
        val top0 = markerTopOnScreen()
        assertEquals("1x 时 marker 高应为 $markerAt1x px（合成图几何自证）", markerAt1x, h0)

        compose.onRoot().performTouchInput { doubleClick(Offset(scanX.toFloat(), focalY)) }
        advance()

        val h1 = markerHeight()
        val ratio = h1.toFloat() / h0
        assertEquals("双击后页面内容应放大到 2.5x（不是只撑高框）", 2.5f, ratio, 0.3f)

        // 像素级锚定：双击点处的文档点在缩放前后停在原位
        // marker 上沿在 1x 文档坐标 = top0，缩放 s 后应落在 focalY + (top0 - focalY) × s
        val expectedTop = focalY + (top0 - focalY) * 2.5f
        assertEquals(
            "双击点处内容应锚定不动（marker 上沿像素位置）",
            expectedTop,
            markerTopOnScreen().toFloat(),
            10f,
        )
    }

    /**
     * 捏合路径：双指由 (180,200)/(180,400) 张开到 (180,100)/(180,500)（质心 y=300 不动，比例 2.0），
     * 与双击共用同一条渲染路径，故同样按「像素实测比例 == 布局缩放倍数」核对。
     */
    @Test
    fun `上下模式捏合后页面内容与布局同步放大`() {
        val bookId = seedBook("updown-render-pinch.cbz")
        compose.setContent { ArkTheme { ReaderScreen(bookId, onBack = {}) } }
        compose.waitUntil(timeoutMillis = 30_000) { pageBounds().size >= 2 }

        val h0 = markerHeight()
        assertEquals("1x 时 marker 高应为 $markerAt1x px（合成图几何自证）", markerAt1x, h0)

        compose.onRoot().performTouchInput {
            pinch(
                start0 = Offset(scanX.toFloat(), 200f),
                end0 = Offset(scanX.toFloat(), 100f),
                start1 = Offset(scanX.toFloat(), 400f),
                end1 = Offset(scanX.toFloat(), 500f),
                durationMillis = 200,
            )
        }
        advance(20)

        // 手指间距 200px → 400px，Compose 的 calculateZoom 逐事件连乘 = 400/200 = 2.0
        val ratio = markerHeight().toFloat() / h0
        assertTrue("捏合后内容应确实放大（实测 $ratio）", ratio > 1.5f)
        assertEquals(
            "两指张开 2.0× 应让页面内容同步放大 2.0×（只撑框不放大图时这里恒为 1.0）",
            2.0f,
            ratio,
            0.3f,
        )
    }
}
