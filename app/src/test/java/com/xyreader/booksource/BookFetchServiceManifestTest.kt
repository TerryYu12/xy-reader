package com.xyreader.booksource

import android.app.Application
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 校验「合并后」的 AndroidManifest（Robolectric 读的正是 AGP 合并产物）里
 * 前台服务与权限声明齐全 —— targetSdk 34 下缺任何一项都会在真机启动服务时抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BookFetchServiceManifestTest {

    private fun packageInfo() = RuntimeEnvironment.getApplication().packageManager.getPackageInfo(
        RuntimeEnvironment.getApplication().packageName,
        PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
    )

    @Test fun declaresForegroundServicePermissions() {
        val requested = packageInfo().requestedPermissions?.toList().orEmpty()
        assertTrue("缺 FOREGROUND_SERVICE", requested.contains("android.permission.FOREGROUND_SERVICE"))
        assertTrue(
            "缺 FOREGROUND_SERVICE_DATA_SYNC（dataSync 类型必需）",
            requested.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"),
        )
        assertTrue("缺 POST_NOTIFICATIONS（Android 13+ 通知）", requested.contains("android.permission.POST_NOTIFICATIONS"))
    }

    @Test fun declaresBookFetchServiceWithDataSyncType() {
        val services = packageInfo().services.orEmpty()
        val service = services.firstOrNull { it.name == BookFetchService::class.java.name }
        assertNotNull("合并后的 manifest 必须声明 BookFetchService", service)
        assertFalse("前台服务不应导出", service!!.exported)
        assertEquals(
            "foregroundServiceType 应为 dataSync",
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            service.foregroundServiceType,
        )
    }
}
