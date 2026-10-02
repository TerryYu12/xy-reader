package com.xyreader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ============================================================
// 色板：对标 Google 相册 / Gmail 的 Material Design 3 表达性设计。
// 暗色为主战场：深蓝黑底 + 浅靛蓝主色 + 珊瑚橙点缀，表面按
// lowest/low/container/high/highest 五层容器色建立清晰层次。
// ============================================================

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1A2150),
    primaryContainer = Color(0xFF3D4488),
    onPrimaryContainer = Color(0xFFE0E4FF),
    secondary = Color(0xFFC3C8F5),
    onSecondary = Color(0xFF23265A),
    secondaryContainer = Color(0xFF363B75),
    onSecondaryContainer = Color(0xFFE0E4FF),
    tertiary = Color(0xFFFFB59D),
    onTertiary = Color(0xFF4A1502),
    tertiaryContainer = Color(0xFF6A2E14),
    onTertiaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF0A0C10),
    onBackground = Color(0xFFE8EAF0),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE8EAF0),
    surfaceVariant = Color(0xFF222835),
    onSurfaceVariant = Color(0xFFA9AFBE),
    surfaceContainerLowest = Color(0xFF0B0E13),
    surfaceContainerLow = Color(0xFF141820),
    surfaceContainer = Color(0xFF1A1F29),
    surfaceContainerHigh = Color(0xFF222835),
    surfaceContainerHighest = Color(0xFF2A3140),
    outline = Color(0xFF3A4250),
    outlineVariant = Color(0xFF262C38),
    inverseSurface = Color(0xFFE8EAF0),
    inverseOnSurface = Color(0xFF171A20),
    inversePrimary = Color(0xFF4355B9),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE1FB),
    onPrimaryContainer = Color(0xFF10164B),
    secondary = Color(0xFF575DB0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E4FA),
    onSecondaryContainer = Color(0xFF161C55),
    tertiary = Color(0xFFB02F35),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD4),
    onTertiaryContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1D24),
    surface = Color.White,
    onSurface = Color(0xFF1A1D24),
    surfaceVariant = Color(0xFFE7E9F2),
    onSurfaceVariant = Color(0xFF5A6070),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4FA),
    surfaceContainer = Color(0xFFEFF1F8),
    surfaceContainerHigh = Color(0xFFEFF1F7),
    surfaceContainerHighest = Color(0xFFE7E9F0),
    outline = Color(0xFF767E93),
    outlineVariant = Color(0xFFDDE1EC),
    inverseSurface = Color(0xFF2F3138),
    inverseOnSurface = Color(0xFFF1F0F6),
    inversePrimary = Color(0xFFB6C0FF),
)

// Google 全家桶形状体系：大圆角表达性设计（8/12/16/24/28）
private val ArkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ArkTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = ArkShapes,
        content = content,
    )
}

// ============================================================
// Google 系柔和点缀色板：设置行 / 入口卡 / 列表行的图标块按各自
// 色相着色。暗色用 Google 暗色变体（浅而柔），亮色用标准变体
// （深而稳）；图标块底色统一取 tint 的 12%~14% 透明度。
// ============================================================
internal enum class AccentColor(val light: Long, val dark: Long) {
    BLUE(0xFF1A73E8, 0xFF8AB4F8),   // 本地仓库 / 信息说明
    GREEN(0xFF188038, 0xFF81C995),  // 远程仓库 / 历史
    PURPLE(0xFF9334E6, 0xFFC58AF9), // 书架分组
    ORANGE(0xFFE8710A, 0xFFFDD663), // 标签
    CYAN(0xFF129EAF, 0xFF78D9EC),   // 阅读配置
    GOLD(0xFFB06000, 0xFFFDD663),   // 书签
    GRAY(0xFF5F6368, 0xFF9AA0A6),   // 版本 / 隐私
}

/** 按当前生效主题取对应的点缀色变体（手动模式经 LocalThemeMode 覆盖系统判断） */
@Composable
internal fun accentColor(color: AccentColor): Color {
    val dark = when (LocalThemeMode.current) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    return if (dark) Color(color.dark.toInt()) else Color(color.light.toInt())
}
