package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 更新检查记录的 DataStore（文件名 "update_prefs"）。
 *
 * 保存上次成功手动检查的时间戳（毫秒）。启动检查不再用它限频；保留历史偏好兼容。
 * 与主题（theme_mode）、阅读配置（reader_prefs）等文件分离——同一 preferences
 * 文件全局只能有一个 DataStore 实例，故每个用途各用一个文件。
 * 写法参照 [ThemePrefsStore]：顶层委托保证实例唯一，多处 new 本类不会重复打开文件。
 */
private val Context.updatePrefsDataStore by preferencesDataStore(name = "update_prefs")

/** 上次检查更新的时刻（System.currentTimeMillis，毫秒）；未写入过为 0 */
private val KEY_LAST_CHECK_AT = longPreferencesKey("last_check_at")

class UpdatePrefsStore(context: Context) {

    private val dataStore = context.applicationContext.updatePrefsDataStore

    /** 上次检查时刻（毫秒）；从未检查过返回 0 */
    val lastCheckAt: Flow<Long> = dataStore.data.map { prefs -> prefs[KEY_LAST_CHECK_AT] ?: 0L }

    /** 记录一次检查时刻（默认取当前时间） */
    suspend fun markChecked(nowMillis: Long = System.currentTimeMillis()) {
        dataStore.edit { prefs -> prefs[KEY_LAST_CHECK_AT] = nowMillis }
    }
}
