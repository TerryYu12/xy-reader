package com.xyreader.ui

import androidx.compose.foundation.border
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xyreader.stats.StreakTier

/**
 * 书库封面边框（连续打卡奖励）：由 MainActivity 在顶层持有并持久化，
 * 通过 [LocalCoverFrame] / [LocalSetCoverFrame] 下发——封面卡片只读一个值，
 * 不必每张卡各自读存储；统计页选择边框时调用 [LocalSetCoverFrame]。
 * 默认 null（不使用边框），因此未提供该 Local 的测试与预览行为不变。
 */
val LocalCoverFrame = staticCompositionLocalOf<StreakTier?> { null }

/** 请求切换封面边框；传 null 取消（内部异步写入 DataStore 并驱动重组） */
val LocalSetCoverFrame = staticCompositionLocalOf<(StreakTier?) -> Unit> { {} }

/** 各档边框的渐变：铜 / 银 / 金为三段线性渐变，紫晶双色，彩虹七色 */
fun StreakTier.frameBrush(): Brush = Brush.linearGradient(frameColors())

/** 档位的主色，徽章与选择面板的标记用 */
fun StreakTier.badgeColor(): Color = when (this) {
    StreakTier.BRONZE -> Color(0xFFB87333)
    StreakTier.SILVER -> Color(0xFF9AA3B2)
    StreakTier.GOLD -> Color(0xFFE0A100)
    StreakTier.AMETHYST -> Color(0xFF8E4DD9)
    StreakTier.RAINBOW -> Color(0xFFE5469B)
}

private fun StreakTier.frameColors(): List<Color> = when (this) {
    StreakTier.BRONZE -> listOf(Color(0xFFB87333), Color(0xFFE8A96B), Color(0xFF8C4A1E))
    StreakTier.SILVER -> listOf(Color(0xFFE8EBF0), Color(0xFF9AA3B2), Color(0xFFF5F7FA))
    StreakTier.GOLD -> listOf(Color(0xFFFFE27A), Color(0xFFE0A100), Color(0xFFFFF0B0))
    StreakTier.AMETHYST -> listOf(Color(0xFFB06AF0), Color(0xFF5B2EA6))
    StreakTier.RAINBOW -> listOf(
        Color(0xFFFF4D4D),
        Color(0xFFFF9A3D),
        Color(0xFFFFD93D),
        Color(0xFF4CD964),
        Color(0xFF3DB8FF),
        Color(0xFF5B6CFF),
        Color(0xFFB04DFF),
    )
}

/**
 * 给封面叠加 [tier] 对应的渐变描边；[tier] 为 null 时原样返回。
 * border 画在内容之上，须放在 clip 之后，与封面同圆角。
 */
fun Modifier.coverFrameBorder(tier: StreakTier?, shape: Shape, width: Dp = 3.dp): Modifier =
    if (tier == null) this else border(width, tier.frameBrush(), shape)
