package com.xyreader.core

import kotlin.math.roundToInt

/**
 * 阅读亮度映射（纯数学，无 Android 依赖，便于单测）。
 *
 * [ReaderPrefs.brightness] 的取值：
 * - `null`：跟随系统；
 * - `0.01..1.0`：窗口亮度，与历史版本完全一致（老配置原样生效）；
 * - `-0.4..0.0`：超低亮度。窗口亮度压到系统允许的最低值，再在整个窗口上盖一层黑色遮罩，
 *   值越小遮罩越浓，从而比系统最低亮度还暗（部分机型系统最低亮度仍然偏亮，窗口亮度无法再降）。
 *
 * 亮度滑条的取值范围就是 [MIN]..[MAX]，滑条值直接存盘，不需要额外换算。
 * 旧版本读到负值会被约束回 0.01，等同系统最低亮度，不会出错。
 */
object BrightnessMath {

    /** 滑条下限：超低亮度区的最深处 */
    const val MIN = -0.4f

    /** 滑条上限：最亮 */
    const val MAX = 1f

    /** 窗口亮度下限：0 在个别机型上会直接关背光，统一用 0.01 */
    const val WINDOW_MIN = 0.01f

    /** 遮罩最浓时的不透明度：此时屏幕亮度约为系统最低亮度的 15% */
    const val MAX_DIM_ALPHA = 0.85f

    /** 把任意输入约束到合法范围；NaN 当作未设置的中间值处理，避免写入非法亮度 */
    fun clamp(value: Float): Float = if (value.isNaN()) WINDOW_MIN else value.coerceIn(MIN, MAX)

    /** 应用到窗口的亮度（0.01..1.0）；超低亮度区一律取下限。`null`（跟随系统）由调用方另行处理 */
    fun windowBrightness(value: Float): Float = clamp(value).coerceIn(WINDOW_MIN, MAX)

    /** 黑色遮罩的不透明度（0..[MAX_DIM_ALPHA]）；跟随系统与普通亮度区为 0 */
    fun dimAlpha(value: Float?): Float {
        if (value == null || value.isNaN() || value >= 0f) return 0f
        return (value.coerceAtLeast(MIN) / MIN) * MAX_DIM_ALPHA
    }

    /** 是否处于「比系统最低亮度还暗」的区间 */
    fun isBelowSystemMin(value: Float?): Boolean = dimAlpha(value) > 0f

    /**
     * 亮度百分比文案：普通区显示窗口亮度（至少 1%），如 `42%`；
     * 超低亮度区显示比系统最低亮度再暗了多少，如 `-40%`。
     */
    fun percentLabel(value: Float): String {
        val dim = dimAlpha(value)
        if (dim > 0f) return "-${(dim * 100).roundToInt()}%"
        return "${(clamp(value) * 100).roundToInt().coerceAtLeast(1)}%"
    }

    /** 靠近下限时提示还能往左拖（让功能可发现）；已进入超低亮度区则说明暗了多少；其余不提示 */
    fun hint(value: Float): String? = when {
        isBelowSystemMin(value) -> "比系统最低亮度再暗 ${(dimAlpha(value) * 100).roundToInt()}%"
        clamp(value) <= HINT_THRESHOLD -> "继续向左拖，可比系统最低亮度更暗"
        else -> null
    }

    /** 亮度低于这个值时提示「继续向左拖」 */
    private const val HINT_THRESHOLD = 0.1f
}
