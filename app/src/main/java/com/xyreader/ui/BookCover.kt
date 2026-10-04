package com.xyreader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat

/**
 * 默认封面预设渐变主题：色板与色标位置取自设计源 index.html 的 `.cover.*`（154°/145°… 斜向渐变，
 * Compose 侧用自上而下的 verticalGradient 近似）。同一本书按书名确定性取其一，配色稳定不跳变。
 *
 * @param symbol 封面中部大字符号（设计稿为与主题绑定的装饰字，非书名/作者数据）
 * @param stops  自顶向下的色标（位置 0f..1f）
 */
internal enum class CoverTheme(
    val symbol: String,
    val stops: List<Pair<Float, Color>>,
) {
    TIDE("潮", listOf(0.00f to Color(0xFF152E50), 0.48f to Color(0xFF37728C), 1f to Color(0xFFEF997C))),
    ORBIT("夜", listOf(0.00f to Color(0xFF171B42), 0.51f to Color(0xFF4B4587), 1f to Color(0xFFE4938C))),
    HARBOR("港", listOf(0.00f to Color(0xFF302439), 0.53f to Color(0xFF9B5966), 1f to Color(0xFFE4B88B))),
    MOON("月", listOf(0.00f to Color(0xFF132D37), 0.55f to Color(0xFF387A72), 1f to Color(0xFFC3BB83))),
    RAIN("雨", listOf(0.00f to Color(0xFF242638), 0.56f to Color(0xFF59658C), 1f to Color(0xFF9DB4C0))),
    LETTERS("信", listOf(0.00f to Color(0xFF4E342A), 0.58f to Color(0xFFAF7B55), 1f to Color(0xFFE1BD8E))),
    UNREAD("阅", listOf(0.00f to Color(0xFF292948), 0.60f to Color(0xFF5D5D94), 1f to Color(0xFFBF94A8)));

    companion object {
        /** 按书名确定性取主题（同一书名永远同一配色）。 */
        fun forTitle(title: String): CoverTheme {
            val list = CoverTheme.entries
            val index = ((title.hashCode() % list.size) + list.size) % list.size
            return list[index]
        }
    }
}

/**
 * 无封面书籍的默认封面（依据设计源 `.cover` 艺术风）：预设渐变底 + 顶部小字行 +
 * 中部大字符号 + 书名 + 左下格式角标。只做视觉呈现，不消费点击、不伪造数据
 * （应用无 author 字段，故省略作者行）。
 *
 * @param compact 小封面（继续阅读焦点卡 / 详情页封面）用紧凑字号并隐藏顶部小字行
 * @param showFormatBadge 是否在左下绘制格式角标；卡片场景已有独立角标时传 false
 */
@Composable
internal fun DefaultBookCover(
    book: BookEntity,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showFormatBadge: Boolean = true,
) {
    val theme = CoverTheme.forTitle(book.title)
    BoxWithConstraints(modifier = modifier.background(Brush.verticalGradient(*theme.stops.toTypedArray()))) {
        val narrow = maxWidth < 120.dp
        val veryNarrow = maxWidth < 92.dp
        val horizontalPad = when {
            veryNarrow -> 6.dp
            compact || narrow -> 8.dp
            else -> 13.dp
        }
        val verticalPad = when {
            veryNarrow -> 6.dp
            compact || narrow -> 8.dp
            else -> 13.dp
        }
        val symbolSize = when {
            veryNarrow -> if (compact) 18.sp else 20.sp
            compact || narrow -> 22.sp
            else -> 34.sp
        }
        val titleSize = when {
            veryNarrow -> 11.sp
            compact -> 13.sp
            narrow -> 12.sp
            else -> 17.sp
        }
        val titleLineHeight = when {
            veryNarrow -> 13.sp
            compact -> 16.sp
            narrow -> 14.sp
            else -> 20.sp
        }

        // 右上装饰圆环（对应 .cover:before 的半透明白描边圆，允许溢出裁切的边缘）
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 18.dp, y = (-10).dp)
                .fillMaxWidth(0.75f)
                .aspectRatio(1f)
                .border(1.dp, Color.White.copy(alpha = 0.28f), CircleShape),
        )

        Column(
            Modifier.fillMaxSize().padding(horizontal = horizontalPad, vertical = verticalPad),
        ) {
            if (!compact) {
                Text(
                    text = "XY·READER",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            // 装饰符号（主题绑定，非书籍数据）
            Text(
                text = theme.symbol,
                fontFamily = FontFamily.Serif,
                fontSize = symbolSize,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
            Spacer(Modifier.height(if (compact || narrow) 4.dp else 9.dp))
            Text(
                text = book.title,
                fontFamily = FontFamily.Serif,
                fontSize = titleSize,
                lineHeight = titleLineHeight,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = if (narrow) 0.1.sp else 0.4.sp,
                color = Color.White,
                maxLines = if (veryNarrow) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            // 格式角标按设计稿 .cover 语义参与流内排版（最后一项），避免与标题在矮封面上重叠
            if (showFormatBadge) {
                Spacer(Modifier.height(if (compact) 7.dp else 9.dp))
                CoverFormatBadge(format = book.format)
            } else {
                // 卡片在封面外叠加格式与进度角标，默认封面需为这些角标保留底边空间。
                Spacer(Modifier.height(if (compact) 24.dp else 28.dp))
            }
        }
    }
}

/** 封面左下格式角标（对应设计源 .cover-format：深色半透明底 + 白色小字）。 */
@Composable
private fun CoverFormatBadge(format: String, modifier: Modifier = Modifier) {
    val label = runCatching { BookFormat.valueOf(format).displayName }.getOrDefault("未知")
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = Color(0x8C0C0F16),
        contentColor = Color.White,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            fontSize = 8.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.7.sp,
            maxLines = 1,
        )
    }
}
