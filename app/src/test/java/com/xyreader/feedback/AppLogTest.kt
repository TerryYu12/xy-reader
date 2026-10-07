package com.xyreader.feedback

import android.util.Log
import com.xyreader.ArkApp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * 日志门面单测：以 [ArkApp] 作为应用类，验证 `ArkApp.onCreate()` 里的 [AppLog.init] 确实把日志接到了
 * `cacheDir/logs/`，且调用仍原样转发到 logcat。引擎本身的细节由 [AppLogFileTest] 覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = ArkApp::class)
class AppLogTest {

    @Test fun arkAppInitializesFileLoggingInsideTheCacheDirectory() {
        val app = RuntimeEnvironment.getApplication()
        AppLog.i("AppLogTest", "hello password=hunter2")

        val all = AppLog.readAll()
        assertTrue(all.contains("I/AppLogTest: hello password=***"))
        assertFalse("写盘前必须已脱敏", all.contains("hunter2"))

        val file = File(app.cacheDir, "logs/${AppLogFile.CURRENT_NAME}")
        assertTrue("应写入 cacheDir/logs/app.log", file.isFile)
        assertTrue(file.readText().contains("I/AppLogTest: hello password=***"))
    }

    @Test fun callsAreStillForwardedToLogcat() {
        ShadowLog.clear()
        val error = IllegalStateException("boom")
        AppLog.w("AppLogForward", "careful", error)
        AppLog.d("AppLogForward", "detail")

        val items = ShadowLog.getLogsForTag("AppLogForward")
        assertEquals(2, items.size)
        assertEquals(Log.WARN, items[0].type)
        assertEquals("careful", items[0].msg)
        assertNotNull(items[0].throwable)
        assertEquals(Log.DEBUG, items[1].type)
        assertEquals("detail", items[1].msg)
    }

    @Test fun exceptionsAreRecordedWithTheirStackTrace() {
        AppLog.e("AppLogTest", "打开失败", IllegalStateException("root cause"))
        val all = AppLog.readAll()
        assertTrue(all.contains("E/AppLogTest: 打开失败"))
        assertTrue(all.contains("java.lang.IllegalStateException: root cause"))
    }
}
