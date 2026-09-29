package com.xyreader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xyreader.ui.ArkNavHost
import com.xyreader.ui.ArkTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 外部「用其他应用打开 / 分享」进来：投递给导航层导入并直接打开
        SharedIntake.submit(intent)
        setContent {
            ArkTheme {
                ArkNavHost()
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
