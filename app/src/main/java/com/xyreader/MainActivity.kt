package com.xyreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xyreader.data.ThemePrefsStore
import com.xyreader.ui.ArkNavHost
import com.xyreader.ui.ArkTheme
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
            val accent by themePrefs.accent.collectAsStateWithLifecycle(
                initialValue = ThemeAccent.LAVENDER,
            )
            CompositionLocalProvider(
                LocalThemeMode provides themeMode,
                LocalSetThemeMode provides { mode -> scope.launch { themePrefs.set(mode) } },
                LocalThemeAccent provides accent,
                LocalSetThemeAccent provides { a -> scope.launch { themePrefs.setAccent(a) } },
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
