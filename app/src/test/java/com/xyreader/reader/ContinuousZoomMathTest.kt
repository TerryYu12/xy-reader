package com.xyreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下滚动模式「同步缩放」的纯函数数学：
 * - [anchoredScrollOffset]：捏合缩放时保持焦点处文档点不动的滚动锚定；
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
}
