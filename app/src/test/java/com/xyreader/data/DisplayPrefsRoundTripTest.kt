package com.xyreader.data

import android.app.Application
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
class DisplayPrefsRoundTripTest {
    @Test fun defaultIsAutoRotateOffAndWritesSurviveStoreRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        // 未写入过时默认关闭（锁定竖屏）
        assertEquals(false, DisplayPrefsStore(context).autoRotate.first())
        // 写入后换新实例读回，验证已落盘
        DisplayPrefsStore(context).setAutoRotate(true)
        assertEquals(true, DisplayPrefsStore(context).autoRotate.first())
        DisplayPrefsStore(context).setAutoRotate(false)
        assertEquals(false, DisplayPrefsStore(context).autoRotate.first())
    }
}
