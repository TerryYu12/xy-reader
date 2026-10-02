package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xyreader.ui.ThemeAccent
import com.xyreader.ui.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 应用主题模式的 DataStore。与阅读配置（"reader_prefs"）、首页布局
 * （"library_layout"）分开文件存放：同一个 preferences 文件全局只能存在
 * 一个 DataStore 实例，故委托在本文件顶层定义一次，文件名 "theme_mode"
 * 与其余两个实例互不冲突。
 */
private val Context.themeModeDataStore by preferencesDataStore(name = "theme_mode")

/** 存 ThemeMode.name；缺失或非法值回退跟随系统 */
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

/** 存 ThemeAccent.name；缺失或非法值回退默认蓝紫 */
private val KEY_ACCENT = stringPreferencesKey("accent_color")

/**
 * 主题模式（跟随系统 / 深色 / 浅色）的持久化读写。
 *
 * 只读写一个枚举，故无需经 AppGraph 全局单例；顶层的 DataStore 委托
 * 已保证实例唯一，多处 new 本类不会重复打开文件。
 */
class ThemePrefsStore(context: Context) {

    private val dataStore = context.applicationContext.themeModeDataStore

    /** 当前主题模式；未写入过或值非法时回退 [ThemeMode.SYSTEM] */
    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_THEME_MODE]
        raw?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    /** 写入主题模式 */
    suspend fun set(mode: ThemeMode) {
        dataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
    }

    /** 当前强调色；未写入过或值非法时回退 [ThemeAccent.LAVENDER] */
    val accent: Flow<ThemeAccent> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_ACCENT]
        raw?.let { runCatching { ThemeAccent.valueOf(it) }.getOrNull() } ?: ThemeAccent.LAVENDER
    }

    /** 写入强调色 */
    suspend fun setAccent(accent: ThemeAccent) {
        dataStore.edit { prefs -> prefs[KEY_ACCENT] = accent.name }
    }
}
