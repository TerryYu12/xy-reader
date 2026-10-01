package com.xyreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下滚动模式「同步缩放」的纯函数数学：
 * - [anchoredScrollOffset]：捏合缩放时保持焦点处文档点不动的滚动锚定；
 * - [ContinuousZoomMath.anchorForScale]：双击缩放动画的逐帧锚定（每帧重算，误差不累积）；
 * - [clampPanX]：放大后水平平移的钳制范围。
 */
class ContinuousZoomMathTest {

    @Test
    fun `放大时锚定滚动偏移保持焦点文档点不动`() {
        // 视口内偏移 100，焦点在视口 y=50：文档点 = 150；放大 2 倍后应为 300 - 50 = 250
        assertEquals(250f, anchoredScrollOffset(100, 50f, 2f), 0.01f)
    }

    @Test
    fun `缩小时锚定滚动偏移同样成立`() {
        // 250 → ratio 0.5：((250+50)*0.5) - 50 = 100
        assertEquals(100f, anchoredScrollOffset(250, 50f, 0.5f), 0.01f)
    }

    @Test
    fun `倍率为一时偏移不变`() {
        assertEquals(123f, anchoredScrollOffset(123, 60f, 1f), 0.01f)
    }

    @Test
    fun `顶到文档开头时不出现负偏移`() {
        assertEquals(0f, anchoredScrollOffset(0, 10f, 0.5f), 0.01f)
    }

    @Test
    fun `水平平移钳制在放大溢出范围内`() {
        // 1080 宽、2 倍放大：左右各溢出 540
        assertEquals(540f, clampPanX(9999f, 1080, 2f), 0.01f)
        assertEquals(-540f, clampPanX(-9999f, 1080, 2f), 0.01f)
        assertEquals(300f, clampPanX(300f, 1080, 2f), 0.01f)
    }

    @Test
    fun `未放大时平移恒为零`() {
        assertEquals(0f, clampPanX(50f, 1080, 1f), 0.01f)
    }

    @Test
    fun `topPixel 累加前面各项并乘缩放倍数`() {
        val heights = listOf(100f, 200f, 300f)
        assertEquals(0f, ContinuousZoomMath.topPixel(heights, 0, 2f), 0.01f)
        assertEquals(600f, ContinuousZoomMath.topPixel(heights, 2, 2f), 0.01f)
        // 越界下标按末尾钳制，不抛异常
        assertEquals(1200f, ContinuousZoomMath.topPixel(heights, 99, 2f), 0.01f)
    }

    @Test
    fun `locate 把像素偏移还原成项下标与项内偏移`() {
        val heights = listOf(100f, 200f, 300f)
        // 缩放 1 倍：0→(0,0)、150→(1,50)、320→(2,20)
        assertEquals(0 to 0f, ContinuousZoomMath.locate(heights, 0f, 1f))
        assertEquals(1 to 50f, ContinuousZoomMath.locate(heights, 150f, 1f))
        assertEquals(2 to 20f, ContinuousZoomMath.locate(heights, 320f, 1f))
    }

    @Test
    fun `locate 在缩放两倍时按放大后高度落位`() {
        val heights = listOf(100f, 200f, 300f)
        // 2 倍：第 0 项 [0,200)、第 1 项 [200,600)
        assertEquals(1 to 100f, ContinuousZoomMath.locate(heights, 300f, 2f))
    }

    @Test
    fun `locate 超出尾部时落在最后一项末尾`() {
        val heights = listOf(100f, 200f)
        val (index, offset) = ContinuousZoomMath.locate(heights, 9999f, 1f)
        assertEquals(1, index)
        assertEquals(200f, offset, 0.01f)
    }

    // —— 缩放动画的逐帧锚定（连续列表双击放大/缩回）——

    /** 动画第 step/total 帧的缩放值（等价 animate 的线性插值，测试无需 Compose 帧时钟） */
    private fun frameScale(from: Float, to: Float, step: Int, total: Int): Float =
        from + (to - from) * step.toFloat() / total.toFloat()

    /** 焦点屏幕 y 处文档点在 scale 下的文档坐标（视口顶部绝对像素 = 项首像素 + 项内偏移） */
    private fun docAtFocal(heights: List<Float>, index: Int, offsetInItem: Float, scale: Float, focalY: Float) =
        (ContinuousZoomMath.topPixel(heights, index, scale) + offsetInItem + focalY) / scale

    @Test
    fun `放大动画逐帧锚定让焦点文档点全程不动`() {
        val heights = listOf(800f, 1200f, 900f, 1500f, 1000f)
        val from = 1f
        val to = 2.5f
        val focalY = 640f
        val startPixel = 1700f
        val expected = (startPixel + focalY) / from
        for (step in 0..8) {
            val current = frameScale(from, to, step, 8)
            val (index, offsetInItem) =
                ContinuousZoomMath.anchorForScale(heights, startPixel, focalY, from, current)
            assertEquals(
                "1x→2.5x 第 $step 帧焦点文档点漂移",
                expected,
                docAtFocal(heights, index, offsetInItem, current, focalY),
                0.1f,
            )
        }
    }

    @Test
    fun `缩回动画逐帧锚定同样让焦点文档点不动`() {
        for (from in listOf(2.5f, 1.5f)) {
            val heights = listOf(1000f, 1000f, 1000f, 1000f)
            val focalY = 300f
            val startPixel = 5000f
            val expected = (startPixel + focalY) / from
            for (step in 0..8) {
                val current = frameScale(from, 1f, step, 8)
                val (index, offsetInItem) =
                    ContinuousZoomMath.anchorForScale(heights, startPixel, focalY, from, current)
                assertEquals(
                    "$from→1x 第 $step 帧焦点文档点漂移",
                    expected,
                    docAtFocal(heights, index, offsetInItem, current, focalY),
                    0.1f,
                )
            }
        }
    }

    @Test
    fun `动画结束帧落点与动画后一次性定位一致`() {
        val heights = listOf(900f, 900f, 900f, 900f, 900f)
        val from = 1f
        val to = 2.5f
        val focalY = 700f
        val startPixel = 1800f
        // 旧实现：动画期间只改 scale，结束后按 to 一次性算落点
        val oneShot = ContinuousZoomMath.locate(
            heights,
            anchoredScrollOffset(startPixel.toInt(), focalY, to / from),
            to,
        )
        assertEquals(oneShot, ContinuousZoomMath.anchorForScale(heights, startPixel, focalY, from, to))
    }

    @Test
    fun `动画起点帧不产生位移`() {
        val heights = listOf(1200f, 1200f, 1200f, 1200f)
        val startPixel = 3600f
        assertEquals(
            ContinuousZoomMath.locate(heights, startPixel, 2.5f),
            ContinuousZoomMath.anchorForScale(heights, startPixel, 500f, 2.5f, 2.5f),
        )
    }

    @Test
    fun `缩回一倍后焦点文档点回到未缩放系统的滚动位置`() {
        val heights = listOf(1000f, 1000f, 1000f, 1000f)
        // 2.5 倍下视口顶部在绝对像素 5000 → 文档坐标 2000；焦点 y=300 处文档点 = 2120
        val startPixel = 5000f
        val (index, offsetInItem) =
            ContinuousZoomMath.anchorForScale(heights, startPixel, 300f, 2.5f, 1f)
        // 缩回后要让它停在屏幕 y=300：滚动偏移 = 2120 - 300 = 1820 → 第 2 项内偏移 820
        assertEquals(1, index)
        assertEquals(820f, offsetInItem, 0.01f)
    }

    @Test
    fun `顶到文档开头时锚定不出现负偏移`() {
        val heights = listOf(1000f, 1000f)
        val (index, offsetInItem) =
            ContinuousZoomMath.anchorForScale(heights, 0f, 100f, 1f, 0.5f)
        assertEquals(0, index)
        assertEquals(0f, offsetInItem, 0.01f)
    }
}
