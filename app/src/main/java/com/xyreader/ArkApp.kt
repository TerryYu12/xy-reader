package com.xyreader

import android.app.Application
import com.xyreader.feedback.AppLog

class ArkApp : Application() {

    override fun onCreate() {
        // 第一行：先装好本地日志与未捕获异常处理器，后续任何初始化阶段的崩溃都能留下现场
        AppLog.init(this)
        AppLog.i("ArkApp", "启动 xy-reader ${BuildConfig.VERSION_NAME}(${BuildConfig.VERSION_CODE})")
        super.onCreate()
    }
}
