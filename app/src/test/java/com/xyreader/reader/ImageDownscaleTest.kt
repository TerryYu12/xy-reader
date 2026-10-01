package com.xyreader.reader

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 图片预缩（高清档）：[ImageDownscale.targetSize] 的判定边界与 [ImageDownscale.downscale] 的实际输出。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDownscaleTest {

    @Test
    fun `targetSize 未超过屏幕短边两倍时无需预缩`() {
        // 上限 = 1080 × DISPLAY_FACTOR(2) = 2160；长边 2000 未超限
        assertNull(ImageDownscale.targetSize(2000, 2000, 1080))
    }

    @Test
    fun `targetSize 超限时按长边等比缩到上限`() {
        // 4000×6000 长边 6000 → 缩到 2160，宽同理缩放 4000×(2160/6000)=1440
        assertEquals(IntSize(1440, 2160), ImageDownscale.targetSize(4000, 6000, 1080))
    }

    @Test
    fun `targetSize 非法输入返回 null`() {
        assertNull(ImageDownscale.targetSize(0, 100, 1080))
        assertNull(ImageDownscale.targetSize(100, 100, 0))
    }

    @Test
    fun `downscale 输出目标尺寸的位图`() {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        try {
            val scaled = ImageDownscale.downscale(source, IntSize(200, 300))
            assertNotNull(scaled)
            assertEquals(200, scaled!!.width)
            assertEquals(300, scaled.height)
        } finally {
            source.recycle()
        }
    }

    @Test
    fun `downscale 目标不小于源时返回 null`() {
        val source = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        try {
            assertNull(ImageDownscale.downscale(source, IntSize(400, 600)))
        } finally {
            source.recycle()
        }
    }
}
