package com.xyreader.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xyreader.stats.StreakTier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 书库封面边框偏好单独存放（与 display_prefs 等分文件；同一个 preferences 文件
 * 全局只能有一个 DataStore 实例，故委托在本文件顶层定义一次）。
 */
private val Context.coverFrameDataStore by preferencesDataStore(name = "cover_frame_prefs")

/** 取值为 [NONE_VALUE] 或 [StreakTier.name] */
private val KEY_COVER_FRAME = stringPreferencesKey("cover_frame")

private const val NONE_VALUE = "NONE"

/**
 * 已选封面边框（连续打卡奖励）的持久化读写；null 表示不使用边框。
 * 只读写一个字符串，不必经 AppGraph；顶层委托保证实例唯一。
 * 是否已解锁由统计页在选择时把关，这里只负责存取，未知值回退 null。
 */
class CoverFrameStore(context: Context) {

    private val dataStore = context.applicationContext.coverFrameDataStore

    /** 当前选中的边框；未设置、选「无」或值无法识别时为 null */
    val selected: Flow<StreakTier?> = dataStore.data.map { prefs ->
        decodeCoverFrame(prefs[KEY_COVER_FRAME])
    }

    /** 写入选择；传 null 表示取消边框 */
    suspend fun set(tier: StreakTier?) {
        dataStore.edit { prefs -> prefs[KEY_COVER_FRAME] = tier?.name ?: NONE_VALUE }
    }
}

/** 存储值 → 档位：NONE、空、未知值（如以后版本删掉的档位）都回退 null */
internal fun decodeCoverFrame(value: String?): StreakTier? =
    StreakTier.entries.firstOrNull { it.name == value }
