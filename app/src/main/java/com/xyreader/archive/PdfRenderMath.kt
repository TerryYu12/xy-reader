package com.xyreader.archive

import kotlin.math.max
import kotlin.math.sqrt

/**
 * PDF 页面渲染的缩放计算（纯函数，便于单元测试）。
 *
 * 背景：android.graphics.pdf.PdfRenderer 默认按 PDF 的 72dpi 点尺寸光栅化
 * （A4 = 595×842px），手机上全屏显示需要放大 1.8 倍以上，位图必然模糊。
 * Android 官方为缩放场景提供了 `render(bitmap, clip, transform, mode)` 的
 * transform 参数（文档明确其用途："从 72dpi 点坐标缩放到目标像素坐标"，
 * 并可用于 tile/缩放渲染）。本对象负责计算「放大到目标像素宽 + 上限保护」的缩放值。
 */
internal object PdfRenderMath {

    /** 渲染目标宽度相对屏幕短边的放大余量（覆盖约 1.5 倍双指放大内的清晰度） */
    const val OVERSAMPLE = 1.5f

    /** 单边像素上限：常见 GPU 纹理上限为 4096，超过会被 HWUI 降级（反而更糊） */
    const val MAX_LONG_SIDE = 4096

    /** 总像素上限（极端长页 12M ≈ 46MB ARGB，防 OOM；常规 A4@1080 屏仅约 3.7M） */
    const val MAX_PIXELS = 12_000_000L

    /**
     * 计算渲染缩放：把页面点坐标放大到 [targetWidthPx] 对应的像素宽（至少保持 1 倍点尺寸），
     * 再按单边 / 总像素上限收缩——极端幅面（超长页/超大幅面）时允许缩到 1 倍以下以保护内存。
     */
    fun renderScale(pageWidth: Int, pageHeight: Int, targetWidthPx: Int): Float {
        if (pageWidth <= 0 || pageHeight <= 0) return 1f
        var scale = if (targetWidthPx > 0) {
            max(1f, targetWidthPx.toFloat() / pageWidth)
        } else {
            1f
        }
        val longSide = max(pageWidth, pageHeight)
        val maxScaleBySide = MAX_LONG_SIDE.toFloat() / longSide
        if (scale > maxScaleBySide) scale = maxScaleBySide
        val maxScaleByPixels =
            sqrt(MAX_PIXELS.toDouble() / (pageWidth.toDouble() * pageHeight.toDouble())).toFloat()
        if (scale > maxScaleByPixels) scale = maxScaleByPixels
        return scale.coerceAtLeast(0.1f)
    }
}
