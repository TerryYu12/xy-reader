package com.xyreader.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 单行书名：放得下就正常显示，放不下就横向滚动播放（跑马灯）。
 *
 * 直接常挂 [basicMarquee]：它在内容不超宽时本来就不产生位移动画（等于静态显示），
 * 因此不需要先测量 `didOverflowWidth` 再决定是否启用——那个判断在宽度不受约束的
 * 布局里永远为 false，会让跑马灯完全不生效（实测踩过）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarqueeTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight = FontWeight.Medium,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    BasicText(
        text = text,
        modifier = modifier.basicMarquee(
            iterations = Int.MAX_VALUE,
            initialDelayMillis = 700,
            repeatDelayMillis = 1600,
            velocity = 30.dp,
        ),
        style = style.copy(color = color, fontWeight = fontWeight),
        maxLines = 1,
        overflow = TextOverflow.Clip,
    )
}
