package com.xyreader.data

import android.app.Application
import com.xyreader.ui.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ThemeModeRoundTripTest {
    @Test fun defaultIsSystemAndWritesSurviveStoreRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // 未写入过时回退跟随系统
        assertEquals(ThemeMode.SYSTEM, ThemePrefsStore(context).themeMode.first())
        // 写入后换新实例读回，验证已落盘
        ThemePrefsStore(context).set(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, ThemePrefsStore(context).themeMode.first())
        ThemePrefsStore(context).set(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, ThemePrefsStore(context).themeMode.first())
    }
}
