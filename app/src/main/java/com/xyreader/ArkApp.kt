package com.xyreader

import android.app.Application
import com.xyreader.feedback.AppLog

class ArkApp : Application() {

    override fun onCreate() {
        // 第一行：先装好本地日志与未捕获异常处理器，后续任何初始化阶段的崩溃都能留下现场
        AppLog.init(this)
        super.onCreate()
    }
}
