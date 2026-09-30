package com.xyreader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xyreader.core.ReaderPrefs
import java.util.Locale
import kotlin.math.roundToInt

/** 小说正文字距、行距与四边页边距；布局和快捷面板共用同一组控件。 */
@Composable
fun NovelSpacingControls(
    prefs: ReaderPrefs,
    onUpdate: ((ReaderPrefs) -> ReaderPrefs) -> Unit,
    compact: Boolean = false,
) {
    val controls: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            SpacingSliderRow(
                title = "行间距",
                value = prefs.novelLineSpacingMultiplier,
                valueRange = 0.8f..2.5f,
                steps = 16,
                valueLabel = { "${String.format(Locale.ROOT, "%.1f", it)}×" },
                onCommit = { value -> onUpdate { it.copy(novelLineSpacingMultiplier = value) } },
            )
            SpacingSliderRow(
                title = "上边距",
                value = prefs.novelMarginTopPx,
                valueRange = 0f..96f,
                steps = 47,
                valueLabel = { "${it.roundToInt()} px" },
                onCommit = { value -> onUpdate { it.copy(novelMarginTopPx = value) } },
            )
            SpacingSliderRow(
                title = "下边距",
                value = prefs.novelMarginBottomPx,
                valueRange = 0f..96f,
                steps = 47,
                valueLabel = { "${it.roundToInt()} px" },
                onCommit = { value -> onUpdate { it.copy(novelMarginBottomPx = value) } },
            )
            SpacingSliderRow(
                title = "左边距",
                value = prefs.novelMarginLeftPx,
                valueRange = 0f..96f,
                steps = 47,
                valueLabel = { "${it.roundToInt()} px" },
                onCommit = { value -> onUpdate { it.copy(novelMarginLeftPx = value) } },
            )
            SpacingSliderRow(
                title = "右边距",
                value = prefs.novelMarginRightPx,
                valueRange = 0f..96f,
                steps = 47,
                valueLabel = { "${it.roundToInt()} px" },
                onCommit = { value -> onUpdate { it.copy(novelMarginRightPx = value) } },
            )
            SpacingSliderRow(
                title = "字间距",
                value = prefs.novelLetterSpacingPx,
                valueRange = -4f..12f,
                steps = 15,
                valueLabel = { "${it.roundToInt()} px" },
                onCommit = { value -> onUpdate { it.copy(novelLetterSpacingPx = value) } },
            )
        }
    }

    if (compact) {
        Text(
            "小说排版",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        controls()
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(
                    "小说排版",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                controls()
            }
        }
    }
}

@Composable
private fun SpacingSliderRow(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var sliderValue by remember(value) {
        mutableFloatStateOf(value.coerceIn(valueRange.start, valueRange.endInclusive))
    }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                valueLabel(sliderValue),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            onValueChangeFinished = { onCommit(sliderValue) },
            valueRange = valueRange,
            steps = steps,
        )
    }
}
