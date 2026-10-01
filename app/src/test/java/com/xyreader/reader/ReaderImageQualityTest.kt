package com.xyreader.reader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import com.xyreader.core.BookEntity
import com.xyreader.core.ImageQuality
import com.xyreader.core.ReaderPrefs
import com.xyreader.data.AppGraph
import com.xyreader.data.ArkDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 图片渲染质量端到端（真实 CBZ → 解码 → 预缩/还原 → 发布 → 切档刷新）：
 * - 高清档：超过「屏幕短边 × 2」的页面在发布前被预缩到上限内；
 * - 切回标准档：位图缓存失效并重新渲染，恢复原始解码尺寸。
 *
 * 说明：不引入 compose 测试规则——直接构造 [ReaderViewModel]，主 Looper 用
 * ShadowLooper.idle() 手动泵动（viewModelScope 的任务都排在主 Looper 上）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderImageQualityTest {

    private val pageWidth = 2000
    private val pageHeight = 3000

    @Test fun highQualityPreScalesAndStandardRestoresFullSize() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.cacheDir, "huge.cbz")
        ZipOutputStream(file.outputStream()).use { zip ->
            val bitmap = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.DKGRAY)
            zip.putNextEntry(ZipEntry("1.png"))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
            zip.closeEntry()
            bitmap.recycle()
        }
        val bookId = runBlocking {
            AppGraph.libraryRepository(context).setReaderPrefs(ReaderPrefs(imageQuality = ImageQuality.HIGH))
            ArkDatabase.getInstance(context).bookDao().insertBook(
                BookEntity(
                    title = "高清预缩测试",
                    uri = Uri.fromFile(file).toString(),
                    format = "CBZ",
                    addedAt = 321,
                ),
            )
        }
        val vm = ReaderViewModel(bookId, initialPage = 0, startFromBeginning = false, app = context)
        awaitPhase(vm, ReaderPhase.Ready, 30_000)
        vm.requestPage(0)

        val metrics = context.resources.displayMetrics
        val shortSide = minOf(metrics.widthPixels, metrics.heightPixels)
        val expected = ImageDownscale.targetSize(pageWidth, pageHeight, shortSide)!!
        val high = awaitReady(vm, expected.width, 30_000)
        assertEquals("预缩后高度应到达上限", expected.height, high.bitmap.height)

        // 切回标准档：缓存失效 + 重新渲染，位图恢复原始尺寸
        runBlocking {
            AppGraph.libraryRepository(context).setReaderPrefs(ReaderPrefs(imageQuality = ImageQuality.STANDARD))
        }
        val restored = awaitReady(vm, pageWidth, 30_000)
        assertEquals(pageHeight, restored.bitmap.height)
    }

    /** 泵一次主 Looper（执行 viewModelScope 上排队的任务） */
    private fun pump() = shadowOf(Looper.getMainLooper()).idle()

    private fun awaitPhase(vm: ReaderViewModel, phase: ReaderPhase, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump()
            if (vm.state.value.phase == phase) return
            Thread.sleep(20)
        }
        throw AssertionError(
            "等待 phase=$phase 超时；当前=${vm.state.value.phase} err=${vm.state.value.errorMessage}",
        )
    }

    private fun awaitReady(vm: ReaderViewModel, width: Int, timeoutMs: Long): PageUi.Ready {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: PageUi? = null
        while (System.currentTimeMillis() < deadline) {
            pump()
            val ui = vm.pages[0]
            last = ui
            if (ui is PageUi.Ready && ui.bitmap.width == width) return ui
            if (ui is PageUi.Failed) throw AssertionError("第 1 页渲染失败")
            Thread.sleep(20)
        }
        val actual = (last as? PageUi.Ready)?.bitmap?.let { "${it.width}x${it.height}" } ?: last.toString()
        throw AssertionError("等待位图宽度=$width 超时；实际=$actual")
    }
}
