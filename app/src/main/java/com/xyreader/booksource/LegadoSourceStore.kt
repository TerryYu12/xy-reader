package com.xyreader.booksource

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.bookSourceDataStore by preferencesDataStore(name = "book_sources")

/**
 * 用户导入的书源仓库（本机持久化）。
 *
 * 设计取舍：
 * - 存 DataStore 单键 JSON（与其他偏好存储同一形态），**不新增 Room 表**——避免数据库迁移；
 * - 身份用 `bookSourceUrl`（去重、启停、删除都按它），书名变化不影响；
 * - 只存源**规则**，不存任何凭据（登录源本阶段不支持）。
 *
 * 注意：仓库本身不携带任何源数据，这里存的全是用户自己导入的。
 */
class LegadoSourceStore(private val context: Context) {

    /** 全部已导入源（按导入顺序） */
    val sources: Flow<List<LegadoBookSource>> = context.bookSourceDataStore.data.map { prefs ->
        decode(prefs[KEY_SOURCES])
    }

    /**
     * 导入一批源：按 URL 去重（已存在则**更新规则、保留启停状态**），返回新增数量。
     *
     * 单条坏源解析失败由 [LegadoBookSource.parseImport] 过滤在前，这里只处理结构正确的源。
     */
    suspend fun import(imported: List<LegadoBookSource>): Int = withContext(Dispatchers.IO) {
        if (imported.isEmpty()) return@withContext 0
        var added = 0
        context.bookSourceDataStore.edit { prefs ->
            val current = decode(prefs[KEY_SOURCES]).associateBy { it.url }.toMutableMap()
            imported.forEach { source ->
                val existing = current[source.url]
                if (existing == null) {
                    current[source.url] = source
                    added++
                } else {
                    // 规则更新，但保留用户在本机的启停选择
                    current[source.url] = source.copy(enabled = existing.enabled)
                }
            }
            prefs[KEY_SOURCES] = encode(current.values.toList())
        }
        added
    }

    /** 删除一条源（按 URL） */
    suspend fun remove(sourceUrl: String): Unit = withContext(Dispatchers.IO) {
        context.bookSourceDataStore.edit { prefs ->
            val remaining = decode(prefs[KEY_SOURCES]).filterNot { it.url == sourceUrl }
            prefs[KEY_SOURCES] = encode(remaining)
        }
    }

    /** 启用/停用一条源 */
    suspend fun setEnabled(sourceUrl: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        context.bookSourceDataStore.edit { prefs ->
            val updated = decode(prefs[KEY_SOURCES]).map { source ->
                if (source.url == sourceUrl) source.copy(enabled = enabled) else source
            }
            prefs[KEY_SOURCES] = encode(updated)
        }
    }

    /** 清空全部源 */
    suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        context.bookSourceDataStore.edit { prefs -> prefs.remove(KEY_SOURCES) }
    }

    private fun decode(raw: String?): List<LegadoBookSource> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { runCatching { LegadoBookSource.fromJson(it) }.getOrNull() }
            }
        }.getOrDefault(emptyList())
    }

    /** 存储用最小字段集：够还原规则与启停，不存 raw（避免把导入时的原始 JSON 长期留着） */
    private fun encode(sources: List<LegadoBookSource>): String {
        val array = JSONArray()
        sources.forEach { source ->
            array.put(
                JSONObject()
                    .put("bookSourceName", source.name)
                    .put("bookSourceUrl", source.url)
                    .put("bookSourceType", source.type.code)
                    .put("bookSourceGroup", source.group ?: "")
                    .put("bookSourceComment", source.comment ?: "")
                    .put("enabled", source.enabled)
                    .put("enabledExplore", source.enabledExplore)
                    .put("header", source.header ?: "")
                    .put("loginUrl", source.loginUrl ?: "")
                    .put("searchUrl", source.searchUrl ?: "")
                    .put("exploreUrl", source.exploreUrl ?: "")
                    .put("bookUrlPattern", source.bookUrlPattern ?: "")
                    .put("concurrentRate", source.concurrentRate ?: "")
                    .put("ruleSearch", ruleGroup(source.ruleSearch))
                    .put("ruleBookInfo", ruleGroup(source.ruleBookInfo))
                    .put("ruleToc", ruleGroup(source.ruleToc))
                    .put("ruleContent", ruleGroup(source.ruleContent)),
            )
        }
        return array.toString()
    }

    private fun ruleGroup(value: Any): JSONObject = when (value) {
        is RuleSearch -> JSONObject()
            .put("bookList", value.bookList).put("name", value.name).put("author", value.author)
            .put("kind", value.kind).put("coverUrl", value.coverUrl).put("intro", value.intro)
            .put("wordCount", value.wordCount).put("lastChapter", value.lastChapter)
            .put("bookUrl", value.bookUrl).put("checkKeyWord", value.checkKeyWord)

        is RuleBookInfo -> JSONObject()
            .put("init", value.init).put("name", value.name).put("author", value.author)
            .put("kind", value.kind).put("intro", value.intro).put("coverUrl", value.coverUrl)
            .put("tocUrl", value.tocUrl).put("wordCount", value.wordCount)
            .put("lastChapter", value.lastChapter).put("canReName", value.canReName)

        is RuleToc -> JSONObject()
            .put("chapterList", value.chapterList).put("chapterName", value.chapterName)
            .put("chapterUrl", value.chapterUrl).put("updateTime", value.updateTime)
            .put("isVip", value.isVip).put("isVolume", value.isVolume).put("nextTocUrl", value.nextTocUrl)

        is RuleContent -> JSONObject()
            .put("content", value.content).put("nextContentUrl", value.nextContentUrl)
            .put("replaceRegex", value.replaceRegex).put("imageStyle", value.imageStyle)
            .put("sourceRegex", value.sourceRegex).put("webJs", value.webJs).put("title", value.title)

        else -> JSONObject()
    }

    private companion object {
        val KEY_SOURCES = stringPreferencesKey("sources")
    }
}
