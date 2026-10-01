package com.xyreader.reader

import android.graphics.Bitmap
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.BitmapCompat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 图片显示前的多级高质量预缩（"高清"渲染档）。
 *
 * 为什么需要：漫画扫描件常见 1600–4000px 宽，手机按屏幕宽度显示要缩小 1.5–3 倍；
 * Android 上 Compose 的 FilterQuality 实际只有 None / Low 两档
 * （Medium / High 与 Low 等效——`AndroidPaint_androidKt.setNativeFilterQuality`
 * 只做 `isFilterBitmap = value != None`），单步双线性在大比例缩小时欠采样，
 * 线条与网点（截网）细节丢失，观感即"糊"。
 *
 * 做法：解码后若图明显大于显示需求，先用 [BitmapCompat.createScaledBitmap]
 * （androidx 官方实现，逐级减半滤波、接近 mipmap 生成过程）缩到
 * 「屏幕短边 × [DISPLAY_FACTOR]」，再交给 GPU 缩放（此时缩小比例 ≤ 2 倍，双线性干净）。
 * 副产物是缓存位图更小、内存占用反而下降。
 *
 * 取舍：双指放大超过 [DISPLAY_FACTOR] 倍时清晰度上限低于原图（源像素已被裁掉）；
 * 2 倍以内放大无损。
 */
internal object ImageDownscale {

    /**
     * 预缩目标相对屏幕短边的倍数：2 = 覆盖 2 倍以内的双指放大，
     * 同时保证交给 GPU 的缩小比例不超过 2 倍（双线性采样的清晰区间）。
     */
    const val DISPLAY_FACTOR = 2

    /**
     * 计算预缩目标尺寸；无需预缩（图未超过屏幕短边 × [DISPLAY_FACTOR]）返回 null。
     *
     * [screenShortSidePx] 取屏幕宽高较短一边：竖屏读的是屏宽、横屏读的是屏高，
     * 两种方向下图的实际显示宽度都不会超过它。
     */
    fun targetSize(width: Int, height: Int, screenShortSidePx: Int): IntSize? {
        if (width <= 0 || height <= 0 || screenShortSidePx <= 0) return null
        val maxSide = max(width, height)
        val limit = screenShortSidePx * DISPLAY_FACTOR
        if (maxSide <= limit) return null
        val ratio = limit.toFloat() / maxSide
        return IntSize(
            (width * ratio).roundToInt().coerceAtLeast(1),
            (height * ratio).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * 多级高质量缩小；[target] 不小于原图或缩放失败时返回 null（调用方回退原图）。
     * 源位图不会被修改。
     */
    fun downscale(bitmap: Bitmap, target: IntSize): Bitmap? = runCatching {
        if (target.width >= bitmap.width || target.height >= bitmap.height) return@runCatching null
        // scaleInLinearSpace = false：沿用源位图色彩空间（漫画图 sRGB，无需线性空间换算）
        BitmapCompat.createScaledBitmap(bitmap, target.width, target.height, null, false)
    }.getOrNull()
}
