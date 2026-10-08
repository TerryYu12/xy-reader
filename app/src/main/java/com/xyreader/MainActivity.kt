package com.xyreader

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.view.WindowCompat
import com.xyreader.data.CoverFrameStore
import com.xyreader.data.DisplayPrefsStore
import com.xyreader.data.ThemePrefsStore
import com.xyreader.ui.ArkNavHost
import com.xyreader.ui.ArkTheme
import com.xyreader.ui.LocalAutoRotate
import com.xyreader.ui.LocalCoverFrame
import com.xyreader.ui.LocalSetCoverFrame
import com.xyreader.ui.LocalSetAutoRotate
import com.xyreader.ui.LocalSetThemeAccent
import com.xyreader.ui.LocalSetThemeMode
import com.xyreader.ui.LocalThemeAccent
import com.xyreader.ui.LocalThemeMode
import com.xyreader.ui.ThemeAccent
import com.xyreader.ui.ThemeMode
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 外部「用其他应用打开 / 分享」进来：投递给导航层导入并直接打开
        SharedIntake.submit(intent)
        setContent {
            // 主题模式：DataStore 持久化，默认跟随系统；经 CompositionLocal 下发，
            // 任意 UI 层可读当前模式或请求切换（首页顶栏切换钮消费 LocalThemeMode/LocalSetThemeMode）。
            val themePrefs = remember { ThemePrefsStore(applicationContext) }
            // 显示行为（自动旋屏）：与主题同一个顶层持有，经 CompositionLocal 下发
            val displayPrefs = remember { DisplayPrefsStore(applicationContext) }
            // 书库封面边框（连续打卡奖励）：同样顶层持有，封面卡只读 LocalCoverFrame
            val coverFrameStore = remember { CoverFrameStore(applicationContext) }
            val scope = rememberCoroutineScope()
            val themeMode by themePrefs.themeMode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            // SYSTEM 交给系统明暗判断，DARK / LIGHT 强制覆盖
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !darkTheme
                insetsController.isAppearanceLightNavigationBars = !darkTheme
            }
            val accent by themePrefs.accent.collectAsStateWithLifecycle(
                initialValue = ThemeAccent.LAVENDER,
            )
            // 自动旋屏（重力感应）：默认关闭（锁定竖屏）
            val autoRotate by displayPrefs.autoRotate.collectAsStateWithLifecycle(
                initialValue = false,
            )
            val coverFrame by coverFrameStore.selected.collectAsStateWithLifecycle(
                initialValue = null,
            )
            // 开启 = 本 Activity 跟随重力传感器自由旋转（不受系统「自动旋转」总开关影响）；
            // 关闭 = 锁定竖屏。阅读器内的「屏幕方向」是更具体的覆盖项，进入阅读器时由
            // ReaderScreen 接管，离开时按本开关恢复，故两处不会互相打架。
            LaunchedEffect(autoRotate) {
                requestedOrientation = if (autoRotate) {
                    ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }
            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalSetThemeMode provides { mode -> scope.launch { themePrefs.set(mode) } },
                LocalThemeAccent provides accent,
                LocalSetThemeAccent provides { a -> scope.launch { themePrefs.setAccent(a) } },
                LocalAutoRotate provides autoRotate,
                LocalSetAutoRotate provides { enabled -> scope.launch { displayPrefs.setAutoRotate(enabled) } },
                LocalCoverFrame provides coverFrame,
                LocalSetCoverFrame provides { tier -> scope.launch { coverFrameStore.set(tier) } },
            ) {
                ArkTheme(darkTheme = darkTheme, accent = accent) {
                    ArkNavHost()
                }
            }
        }
    }

    /** 应用已在前台时被再次投递（QQ/微信/文件管理器二次打开） */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        SharedIntake.submit(intent)
    }
}
