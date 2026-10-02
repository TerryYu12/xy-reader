package com.xyreader.ui

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 应用强调色（主题色）：用户在「设置 → 外观 → 强调色」中选择，
 * 驱动全局主色系（primary / onPrimary / primaryContainer / onPrimaryContainer），
 * 图标块、按钮、选中态、滑块、进度条等随之整体切换。
 *
 * 每档同时定义深色 / 浅色两套值（深色用浅而柔的变体，浅色用饱和的标准变体）。
 */
enum class ThemeAccent(
    val label: String,
    private val darkPrimary: Long,
    private val darkOnPrimary: Long,
    private val darkContainer: Long,
    private val darkOnContainer: Long,
    private val lightPrimary: Long,
    private val lightOnPrimary: Long,
    private val lightContainer: Long,
    private val lightOnContainer: Long,
) {
    LAVENDER(
        "蓝紫",
        0xFFA5B4FC, 0xFF1A2150, 0xFF3D4488, 0xFFE0E4FF,
        0xFF4355B9, 0xFFFFFFFF, 0xFFDEE1FB, 0xFF10164B,
    ),
    OCEAN(
        "海蓝",
        0xFF8AB4F8, 0xFF0B1D3A, 0xFF2A4A7F, 0xFFD9E6FF,
        0xFF1A73E8, 0xFFFFFFFF, 0xFFD3E3FD, 0xFF041E49,
    ),
    TEAL(
        "青碧",
        0xFF78D9EC, 0xFF00363D, 0xFF1F5C66, 0xFFD5F3F8,
        0xFF129EAF, 0xFFFFFFFF, 0xFFCDEFF4, 0xFF002F35,
    ),
    GREEN(
        "翠绿",
        0xFF81C995, 0xFF0D2E14, 0xFF2E5B3A, 0xFFD7F3DC,
        0xFF188038, 0xFFFFFFFF, 0xFFD3ECD4, 0xFF062E0B,
    ),
    AMBER(
        "琥珀",
        0xFFFDD663, 0xFF3D2E00, 0xFF6B5310, 0xFFFFEFC3,
        0xFFB06000, 0xFFFFFFFF, 0xFFFFE7C2, 0xFF3A2400,
    ),
    CORAL(
        "珊瑚",
        0xFFFFB59D, 0xFF4A1502, 0xFF6A2E14, 0xFFFFDBCF,
        0xFFB02F35, 0xFFFFFFFF, 0xFFFFDAD4, 0xFF410002,
    ),
    ROSE(
        "玫红",
        0xFFF6A9C5, 0xFF4A1030, 0xFF7A3054, 0xFFFFD9E7,
        0xFFB0326A, 0xFFFFFFFF, 0xFFFFD8E6, 0xFF3E0022,
    ),
    VIOLET(
        "紫罗兰",
        0xFFC58AF9, 0xFF2E0A4C, 0xFF5B2E7E, 0xFFEDDCFC,
        0xFF9334E6, 0xFFFFFFFF, 0xFFEDDCFC, 0xFF2A0050,
    ),
    ;

    /** 深色 / 浅色下的主要强调色（按钮、滑块、进度、选中文字等） */
    fun primary(dark: Boolean): Color = Color((if (dark) darkPrimary else lightPrimary).toInt())

    /** 强调色上的内容色 */
    fun onPrimary(dark: Boolean): Color = Color((if (dark) darkOnPrimary else lightOnPrimary).toInt())

    /** 强调色容器（选中胶囊底、图标块底、色板圆等） */
    fun container(dark: Boolean): Color = Color((if (dark) darkContainer else lightContainer).toInt())

    /** 容器上的内容色 */
    fun onContainer(dark: Boolean): Color = Color((if (dark) darkOnContainer else lightOnContainer).toInt())
}

/** 当前选中的强调色（设置里修改）；默认蓝紫，与旧版主色一致 */
val LocalThemeAccent = staticCompositionLocalOf { ThemeAccent.LAVENDER }

/** 请求切换强调色（内部异步写 DataStore 并驱动重组） */
val LocalSetThemeAccent = staticCompositionLocalOf<(ThemeAccent) -> Unit> { {} }
