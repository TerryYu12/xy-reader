package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用级显示行为偏好（与主题 "theme_mode"、阅读配置 "reader_prefs"、首页布局
 * "library_layout" 分开文件存放：同一个 preferences 文件全局只能存在一个
 * DataStore 实例，故委托在本文件顶层定义一次）。
 */
private val Context.displayPrefsDataStore by preferencesDataStore(name = "display_prefs")

/** 自动旋屏（重力感应）：true = 跟随设备重力自由旋转；false = 锁定竖屏 */
private val KEY_AUTO_ROTATE = booleanPreferencesKey("auto_rotate")

/**
 * 显示行为（目前只有「自动旋屏」）的持久化读写。
 *
 * 只读写一个布尔量，故无需经 AppGraph 全局单例；顶层 DataStore 委托已保证
 * 实例唯一，多处 new 本类不会重复打开文件。
 */
class DisplayPrefsStore(context: Context) {

    private val dataStore = context.applicationContext.displayPrefsDataStore

    /** 自动旋屏开关；未写入过时回退 false（默认锁定竖屏，不跟随重力） */
    val autoRotate: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_AUTO_ROTATE] ?: false
    }

    /** 写入自动旋屏开关 */
    suspend fun setAutoRotate(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_AUTO_ROTATE] = enabled }
    }
}
