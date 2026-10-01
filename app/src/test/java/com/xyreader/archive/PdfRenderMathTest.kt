package com.xyreader.archive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PDF 渲染缩放计算（纯函数）：常规放大、1 倍下界、单边与总像素上限、非法输入。
 */
class PdfRenderMathTest {

    @Test
    fun `常规放大按目标宽度除以页宽`() {
        // 600pt 宽的页渲染到 1620px → 2.7 倍
        assertEquals(1620f / 600f, PdfRenderMath.renderScale(600, 800, 1620), 1e-4f)
    }

    @Test
    fun `目标小于页宽时保持一倍`() {
        assertEquals(1f, PdfRenderMath.renderScale(600, 800, 300), 1e-4f)
    }

    @Test
    fun `长边超过上限时按长边收缩`() {
        // 2000×3000 页放大到 4000px 宽会得到 6000pt 长边，被 4096 上限压回 4096/3000
        assertEquals(4096f / 3000f, PdfRenderMath.renderScale(2000, 3000, 4000), 1e-4f)
    }

    @Test
    fun `总像素超过上限时收缩到上限比例`() {
        // 3000×4000 页放大 3.333 倍会远超 12M 像素，压回 1 倍
        assertEquals(1f, PdfRenderMath.renderScale(3000, 4000, 9999), 1e-4f)
    }

    @Test
    fun `非法页宽回退一倍`() {
        assertEquals(1f, PdfRenderMath.renderScale(0, 800, 1620), 1e-4f)
    }
}
