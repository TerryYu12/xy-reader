package com.xyreader.reader

import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/**
 * 给 [view] 盖一层黑色遮罩（[alpha] 0..1；<= 0 清除）。
 *
 * 用 View.foreground 实现：画在整个 View 树（含状态栏/导航栏底色）之上，不接收也不拦截触摸，
 * 用来做「比系统最低亮度还暗」。对话框、底部面板是独立窗口，需各自调用。
 */
internal fun applyDim(view: View, alpha: Float) {
    view.foreground = if (alpha > 0.001f) {
        ColorDrawable(AndroidColor.argb((alpha.coerceAtMost(1f) * 255f).roundToInt(), 0, 0, 0))
    } else {
        null
    }
}

/**
 * 放在对话框 / 底部面板的内容里：让这个窗口跟随阅读器的超低亮度遮罩，
 * 离开组合时清除。[alpha] 为 0 时什么也不画。
 */
@Composable
internal fun DimThisWindow(alpha: Float) {
    val view = LocalView.current
    DisposableEffect(view, alpha) {
        runCatching { applyDim(view, alpha) }
        onDispose { runCatching { applyDim(view, 0f) } }
    }
}
