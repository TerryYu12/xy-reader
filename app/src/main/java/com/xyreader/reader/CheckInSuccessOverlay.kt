package com.xyreader.reader

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyreader.stats.formatCheckInSubtitle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// —— 时间线（毫秒）：卡片弹入 → 圆环与对勾依次描出 → 星点迸发 → 停留 → 淡出 ——
private const val DRAW_DELAY_MS = 100L
private const val DRAW_MS = 650
private const val BURST_DELAY_MS = 520L
private const val BURST_MS = 750
private const val HOLD_MS = 2300L
private const val EXIT_MS = 350

/** 描边进度里圆环占的份额（前 55% 画圆环，对勾从 45% 起叠着画，衔接更顺） */
private const val RING_SHARE = 0.55f
private const val TICK_START = 0.45f

/** 卡片顶部留白占「屏幕剩余高度」的比例：偏上，但不压到顶栏 */
private const val TOP_SPACE_WEIGHT = 0.16f

private const val BURST_DOTS = 10

/**
 * 「打卡成功」动画：[event] 非空时在屏幕偏上位置弹出一张提示卡，播完自动淡出并回调 [onFinished]。
 * 整体不拦截任何触摸，阅读操作不受影响；每个新的 [event] 实例都会从头播放。
 */
@Composable
internal fun CheckInSuccessOverlay(
    event: CheckInEvent?,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (event == null) return
    key(event) { CheckInSuccessContent(event, onFinished, modifier) }
}

@Composable
private fun CheckInSuccessContent(
    event: CheckInEvent,
    onFinished: () -> Unit,
    modifier: Modifier,
) {
    val enter = remember { Animatable(0f) }
    val draw = remember { Animatable(0f) }
    val burst = remember { Animatable(0f) }
    val exit = remember { Animatable(1f) }
    val finished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        launch { enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow)) }
        launch {
            delay(DRAW_DELAY_MS)
            draw.animateTo(1f, tween(DRAW_MS, easing = FastOutSlowInEasing))
        }
        launch {
            delay(BURST_DELAY_MS)
            burst.animateTo(1f, tween(BURST_MS, easing = LinearOutSlowInEasing))
        }
        delay(HOLD_MS)
        exit.animateTo(0f, tween(EXIT_MS))
        finished()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(TOP_SPACE_WEIGHT))
        Surface(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 340.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
                .graphicsLayer {
                    // 弹簧会略微过冲：透明度夹在 0..1，缩放保留过冲产生的「弹一下」
                    alpha = enter.value.coerceIn(0f, 1f) * exit.value
                    val scale = 0.8f + 0.2f * enter.value
                    scaleX = scale
                    scaleY = scale
                    translationY = (1f - enter.value) * -28.dp.toPx()
                },
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CheckInBadge(drawProgress = { draw.value }, burstProgress = { burst.value })
                Column {
                    Text(
                        text = "打卡成功",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = formatCheckInSubtitle(event.streak),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f - TOP_SPACE_WEIGHT))
    }
}

/**
 * 圆形徽标：淡底 → 圆环顺时针描出 → 对勾描出，对勾落定后向外迸出一圈星点和一道扩散的细环。
 * 迸发半径控制在徽标半径的 1.35 倍内，不会被外层卡片的圆角裁掉。
 */
@Composable
private fun CheckInBadge(
    drawProgress: () -> Float,
    burstProgress: () -> Float,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.size(52.dp)) {
        val radius = size.minDimension / 2f
        val strokeWidth = 3.dp.toPx()
        val drawing = drawProgress()
        val bursting = burstProgress()

        drawCircle(primary.copy(alpha = 0.14f * drawing.coerceIn(0f, 1f)), radius = radius)

        val ring = (drawing / RING_SHARE).coerceIn(0f, 1f)
        val inset = strokeWidth / 2f
        drawArc(
            color = primary,
            startAngle = -90f,
            sweepAngle = 360f * ring,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )

        val tick = ((drawing - TICK_START) / (1f - TICK_START)).coerceIn(0f, 1f)
        if (tick > 0f) {
            val full = Path().apply {
                moveTo(size.width * 0.29f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.67f)
                lineTo(size.width * 0.72f, size.height * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(full, false) }
            val partial = Path()
            measure.getSegment(0f, measure.length * tick, partial, true)
            drawPath(
                path = partial,
                color = primary,
                style = Stroke(width = strokeWidth * 1.15f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        if (bursting > 0f && bursting < 1f) {
            val fade = 1f - bursting
            drawCircle(
                color = primary.copy(alpha = 0.35f * fade),
                radius = radius * (1f + 0.35f * bursting),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            for (i in 0 until BURST_DOTS) {
                val angle = (i * 2.0 * PI / BURST_DOTS) - PI / 2
                val distance = radius * (0.95f + 0.35f * bursting)
                drawCircle(
                    color = (if (i % 2 == 0) primary else accent).copy(alpha = fade),
                    radius = 2.6.dp.toPx() * (1f - 0.5f * bursting),
                    center = Offset(
                        center.x + (cos(angle) * distance).toFloat(),
                        center.y + (sin(angle) * distance).toFloat(),
                    ),
                )
            }
        }
    }
}
