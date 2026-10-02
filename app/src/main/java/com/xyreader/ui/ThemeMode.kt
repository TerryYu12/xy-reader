package com.xyreader.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用主题模式：跟随系统 / 强制深色 / 强制浅色。
 *
 * 由 MainActivity 在 setContent 顶层持有并持久化，通过
 * [LocalThemeMode] / [LocalSetThemeMode] 两个 CompositionLocal 下发，
 * 任何 UI 层（如首页顶栏的主题切换钮）都可读取当前模式或请求切换，
 * 无需自己持有 DataStore。
 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** 当前生效的主题模式；默认跟随系统，与历史行为一致 */
val LocalThemeMode = staticCompositionLocalOf { ThemeMode.SYSTEM }

/** 请求切换主题模式（内部异步写入 DataStore 并驱动重组） */
val LocalSetThemeMode = staticCompositionLocalOf<(ThemeMode) -> Unit> { {} }
