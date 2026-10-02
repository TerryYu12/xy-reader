package com.xyreader.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 应用级显示行为：自动旋屏（重力感应）。
 *
 * 由 MainActivity 在 setContent 顶层持有并持久化，通过 [LocalAutoRotate] /
 * [LocalSetAutoRotate] 两个 CompositionLocal 下发：设置页的开关写入，
 * 阅读器（离开时恢复全局方向策略）读取，无需各自持有 DataStore。
 */

/** 自动旋屏当前开关值；默认关闭（锁定竖屏，与历史默认的竖屏阅读一致） */
val LocalAutoRotate = staticCompositionLocalOf { false }

/** 请求切换自动旋屏（内部异步写入 DataStore 并驱动重组） */
val LocalSetAutoRotate = staticCompositionLocalOf<(Boolean) -> Unit> { {} }
