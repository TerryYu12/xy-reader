package com.xyreader.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** 单页缩放上限（双指捏合与双击共用） */
internal const val PAGE_MAX_ZOOM = 5f

/**
 * 单页缩放状态：双指捏合与双击放大共用同一份 scale/offset，
 * 翻页时由阅读器调用 [reset] 复位。offset 是缩放后内容中心的平移量。
 */
class PageZoomState {
    var scale by mutableFloatStateOf(1f)
        internal set
    var offset by mutableStateOf(Offset.Zero)
        internal set

    internal var containerSize: IntSize = IntSize.Zero

    /** 直接设置缩放（双击动画回调用），并把平移钳制在合法范围 */
    internal fun set(scale: Float) {
        this.scale = scale.coerceIn(1f, PAGE_MAX_ZOOM)
        clampOffset()
    }

    /** 捏合增量：scale 乘上 zoomChange，offset 累加 panChange */
    internal fun applyGesture(zoomChange: Float, panChange: Offset) {
        scale = (scale * zoomChange).coerceIn(1f, PAGE_MAX_ZOOM)
        offset += panChange
        clampOffset()
    }

    /**
     * 连续列表（上下滚动）模式：只做水平平移（纵向平移由列表滚动承担），
     * 平移量按视口宽度钳制在内容左右溢出范围内。
     */
    internal fun panByX(deltaX: Float, viewportWidth: Int) {
        offset = Offset(clampPanX(offset.x + deltaX, viewportWidth, scale), 0f)
    }

    /** 连续列表用：设置缩放但保留水平平移（捏合时不被 clampOffset 清零） */
    internal fun setScalePreservePan(scale: Float) {
        this.scale = scale.coerceIn(1f, PAGE_MAX_ZOOM)
    }

    /** 连续列表用：把水平平移钳制到视口宽度对应的溢出范围 */
    internal fun clampPanToViewport(viewportWidth: Int) {
        offset = Offset(clampPanX(offset.x, viewportWidth, scale), 0f)
    }

    internal fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    /** 平移钳制：内容放大 (scale-1) 倍后，平移量不超过溢出尺寸的一半（配合居中变换） */
    internal fun clampOffset() {
        if (scale <= 1f || containerSize == IntSize.Zero) {
            offset = Offset.Zero
            return
        }
        val maxX = containerSize.width * (scale - 1f) / 2f
        val maxY = containerSize.height * (scale - 1f) / 2f
        offset = Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}

/**
 * 页内缩放手势：
 * - 两指按下 → 进入缩放态，消费事件（Pager 不再翻页），捏合缩放 + 双指拖动平移；
 * - 已放大（scale > 1）时单指拖动 → 平移图片（钳制在内容范围内），不翻页；
 * - 单指且未放大 → 不消费任何事件，翻页 / 点击 / 双击全部照常；
 * - 缩回 1x 且仅剩单指 → 退出缩放态，把剩余手势交还 Pager。
 */
internal fun Modifier.pageZoom(state: PageZoomState): Modifier = this
    .onSizeChanged { state.containerSize = it }
    .pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zooming = false
            do {
                val event = awaitPointerEvent()
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) zooming = true
                if (zooming || state.scale > 1f) {
                    val zoomChange = event.calculateZoom()
                    val panChange = event.calculatePan()
                    state.applyGesture(zoomChange, panChange)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    // 缩回 1x 且只剩单指：本次手势退出缩放态，剩余拖动交还 Pager 翻页
                    if (state.scale <= 1f && pressedCount < 2) {
                        zooming = false
                        state.reset()
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }

/**
 * 连续列表缩放辅助：锚定滚动偏移——保持 [focalY] 处的文档点在缩放前后不动。
 * 近似：内容点（旧偏移 + 焦点）放大 ratio 倍后，滚动偏移 = 点 * ratio - 焦点。
 */
internal fun anchoredScrollOffset(oldOffset: Int, focalY: Float, ratio: Float): Float =
    ((oldOffset + focalY) * ratio - focalY).coerceAtLeast(0f)

/** 水平平移钳制：内容放大 scale 倍后左右各溢出 (scale-1)/2 个视口宽 */
internal fun clampPanX(x: Float, viewportWidth: Int, scale: Float): Float {
    val max = (viewportWidth * (scale - 1f) / 2f).coerceAtLeast(0f)
    return x.coerceIn(-max, max)
}

/**
 * 连续列表位置换算：把「绝对像素偏移」与「列表项 (index, 项内偏移)」互转。
 * [heights] 为每一项在 scale=1 时的高度（像素，项间距为 0）。
 */
internal object ContinuousZoomMath {

    /** 第 [index] 项顶部在缩放 [scale] 下的像素位置 */
    fun topPixel(heights: List<Float>, index: Int, scale: Float): Float {
        var sum = 0f
        val end = index.coerceIn(0, heights.size)
        for (i in 0 until end) sum += heights[i]
        return sum * scale
    }

    /** 把缩放 [scale] 下的绝对像素 [pixel] 定位成 (index, 项内偏移[0, 项高)) */
    fun locate(heights: List<Float>, pixel: Float, scale: Float): Pair<Int, Float> {
        if (heights.isEmpty()) return 0 to 0f
        val p = pixel.coerceAtLeast(0f)
        var acc = 0f
        heights.forEachIndexed { i, h ->
            val scaled = h * scale
            if (p < acc + scaled || i == heights.lastIndex) {
                return i to (p - acc).coerceIn(0f, scaled)
            }
            acc += scaled
        }
        return heights.lastIndex to 0f
    }
}

/**
 * 连续列表（上下滚动模式）的整列缩放手势：
 * - 双指：捏合缩放（质心为焦点）+ 水平平移；缩放后上下两页始终首尾相接；
 * - 捏合后剩单指：延续水平平移；
 * - 已放大时的单指拖动：按主方向分流——水平为主 → 平移内容并消费事件；
 *   垂直为主 → 不消费，列表照常滚动（放大状态下仍能纵向阅读）；
 * - 未放大时单指不消费：点击 / 双击 / 滚动照常。
 * 事件在 Initial 阶段处理，先于 LazyColumn 的滚动逻辑。
 */
internal fun Modifier.columnZoom(
    onZoom: (zoomChange: Float, centroid: Offset) -> Unit,
    onPanX: (Float) -> Unit,
    isZoomed: () -> Boolean,
): Modifier = this
    .pointerInput(Unit) {
        val touchSlop = viewConfiguration.touchSlop
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var zooming = false
            var axisDecided = false
            var horizontalPan = false
            var dxSum = 0f
            var dySum = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
                val pressedCount = event.changes.count { it.pressed }
                if (pressedCount >= 2) {
                    zooming = true
                    val zoomChange = event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    val pan = event.calculatePan()
                    if (zoomChange != 1f) onZoom(zoomChange, centroid)
                    if (pan.x != 0f) onPanX(pan.x)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    if (!isZoomed()) zooming = false
                } else if (zooming) {
                    // 捏合后剩单指：延续水平平移，消费事件防列表抢滚动
                    val pan = event.calculatePan()
                    if (pan.x != 0f) onPanX(pan.x)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                    if (!isZoomed()) zooming = false
                } else if (isZoomed()) {
                    val change = event.changes.firstOrNull { it.pressed }
                    if (change != null) {
                        if (!axisDecided) {
                            val delta = change.positionChange()
                            dxSum += delta.x
                            dySum += delta.y
                            if (kotlin.math.abs(dxSum) > touchSlop ||
                                kotlin.math.abs(dySum) > touchSlop
                            ) {
                                axisDecided = true
                                horizontalPan = kotlin.math.abs(dxSum) > kotlin.math.abs(dySum)
                            }
                        }
                        if (horizontalPan) {
                            onPanX(change.positionChange().x)
                            change.consume()
                        }
                    }
                }
            }
        }
    }
