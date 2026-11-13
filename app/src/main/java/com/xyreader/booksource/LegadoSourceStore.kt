package com.xyreader.booksource

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.StringReader
import java.io.StringWriter

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

    /** 批量删除源（按 URL 集合） */
    suspend fun removeBatch(sourceUrls: Collection<String>): Unit = withContext(Dispatchers.IO) {
        if (sourceUrls.isEmpty()) return@withContext
        val urlSet = sourceUrls.toSet()
        context.bookSourceDataStore.edit { prefs ->
            val remaining = decode(prefs[KEY_SOURCES]).filterNot { it.url in urlSet }
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
            val reader = JsonReader(StringReader(raw))
            val list = mutableListOf<LegadoBookSource>()
            reader.beginArray()
            while (reader.hasNext()) {
                val source = readSource(reader)
                if (source != null) {
                    list.add(source)
                }
            }
            reader.endArray()
            reader.close()
            list
        }.getOrElse {
            // 兜底：流式解析遇到旧格式或不规则数据时，回退到 JSONArray 逐条解析
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { runCatching { LegadoBookSource.fromJson(it) }.getOrNull() }
                }
            }.getOrDefault(emptyList())
        }
    }

    private fun readSource(reader: JsonReader): LegadoBookSource? {
        var name = ""
        var url = ""
        var type = BookSourceType.NOVEL
        var group: String? = null
        var comment: String? = null
        var enabled = true
        var enabledExplore = true
        var header: String? = null
        var loginUrl: String? = null
        var searchUrl: String? = null
        var exploreUrl: String? = null
        var bookUrlPattern: String? = null
        var concurrentRate: String? = null
        var ruleSearch = RuleSearch()
        var ruleBookInfo = RuleBookInfo()
        var ruleToc = RuleToc()
        var ruleContent = RuleContent()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "bookSourceName" -> name = reader.nextSafeString().trim()
                "bookSourceUrl" -> url = reader.nextSafeString().trim()
                "bookSourceType" -> type = BookSourceType.fromCode(reader.nextIntOrDefault(0))
                "bookSourceGroup" -> group = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "bookSourceComment" -> comment = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "enabled" -> enabled = reader.nextBooleanOrDefault(true)
                "enabledExplore" -> enabledExplore = reader.nextBooleanOrDefault(true)
                "header" -> header = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "loginUrl" -> loginUrl = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "searchUrl" -> searchUrl = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "exploreUrl" -> exploreUrl = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "bookUrlPattern" -> bookUrlPattern = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "concurrentRate" -> concurrentRate = reader.nextStringOrNull()?.takeIf { it.isNotBlank() }
                "ruleSearch" -> ruleSearch = readRuleSearch(reader)
                "ruleBookInfo" -> ruleBookInfo = readRuleBookInfo(reader)
                "ruleToc" -> ruleToc = readRuleToc(reader)
                "ruleContent" -> ruleContent = readRuleContent(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (name.isEmpty() || url.isEmpty()) return null
        return LegadoBookSource(
            name = name,
            url = url,
            type = type,
            group = group,
            comment = comment,
            enabled = enabled,
            enabledExplore = enabledExplore,
            header = header,
            loginUrl = loginUrl,
            searchUrl = searchUrl,
            exploreUrl = exploreUrl,
            bookUrlPattern = bookUrlPattern,
            concurrentRate = concurrentRate,
            ruleSearch = ruleSearch,
            ruleBookInfo = ruleBookInfo,
            ruleToc = ruleToc,
            ruleContent = ruleContent,
        )
    }

    private fun readRuleSearch(reader: JsonReader): RuleSearch {
        var bookList = ""
        var name = ""
        var author = ""
        var kind = ""
        var coverUrl = ""
        var intro = ""
        var wordCount = ""
        var lastChapter = ""
        var bookUrl = ""
        var checkKeyWord = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "bookList" -> bookList = reader.nextSafeString()
                "name" -> name = reader.nextSafeString()
                "author" -> author = reader.nextSafeString()
                "kind" -> kind = reader.nextSafeString()
                "coverUrl" -> coverUrl = reader.nextSafeString()
                "intro" -> intro = reader.nextSafeString()
                "wordCount" -> wordCount = reader.nextSafeString()
                "lastChapter" -> lastChapter = reader.nextSafeString()
                "bookUrl" -> bookUrl = reader.nextSafeString()
                "checkKeyWord" -> checkKeyWord = reader.nextSafeString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RuleSearch(bookList, name, author, kind, coverUrl, intro, wordCount, lastChapter, bookUrl, checkKeyWord)
    }

    private fun readRuleBookInfo(reader: JsonReader): RuleBookInfo {
        var init = ""
        var name = ""
        var author = ""
        var kind = ""
        var intro = ""
        var coverUrl = ""
        var tocUrl = ""
        var wordCount = ""
        var lastChapter = ""
        var canReName = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "init" -> init = reader.nextSafeString()
                "name" -> name = reader.nextSafeString()
                "author" -> author = reader.nextSafeString()
                "kind" -> kind = reader.nextSafeString()
                "intro" -> intro = reader.nextSafeString()
                "coverUrl" -> coverUrl = reader.nextSafeString()
                "tocUrl" -> tocUrl = reader.nextSafeString()
                "wordCount" -> wordCount = reader.nextSafeString()
                "lastChapter" -> lastChapter = reader.nextSafeString()
                "canReName" -> canReName = reader.nextSafeString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RuleBookInfo(init, name, author, kind, intro, coverUrl, tocUrl, wordCount, lastChapter, canReName)
    }

    private fun readRuleToc(reader: JsonReader): RuleToc {
        var chapterList = ""
        var chapterName = ""
        var chapterUrl = ""
        var updateTime = ""
        var isVip = ""
        var isVolume = ""
        var nextTocUrl = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "chapterList" -> chapterList = reader.nextSafeString()
                "chapterName" -> chapterName = reader.nextSafeString()
                "chapterUrl" -> chapterUrl = reader.nextSafeString()
                "updateTime" -> updateTime = reader.nextSafeString()
                "isVip" -> isVip = reader.nextSafeString()
                "isVolume" -> isVolume = reader.nextSafeString()
                "nextTocUrl" -> nextTocUrl = reader.nextSafeString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RuleToc(chapterList, chapterName, chapterUrl, updateTime, isVip, isVolume, nextTocUrl)
    }

    private fun readRuleContent(reader: JsonReader): RuleContent {
        var content = ""
        var nextContentUrl = ""
        var replaceRegex = ""
        var imageStyle = ""
        var sourceRegex = ""
        var webJs = ""
        var title = ""
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "content" -> content = reader.nextSafeString()
                "nextContentUrl" -> nextContentUrl = reader.nextSafeString()
                "replaceRegex" -> replaceRegex = reader.nextSafeString()
                "imageStyle" -> imageStyle = reader.nextSafeString()
                "sourceRegex" -> sourceRegex = reader.nextSafeString()
                "webJs" -> webJs = reader.nextSafeString()
                "title" -> title = reader.nextSafeString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return RuleContent(content, nextContentUrl, replaceRegex, imageStyle, sourceRegex, webJs, title)
    }

    /** 存储用最小字段集：流式写入避免大量 JSONObject 导致 OOM，省略空规则字段以压缩体积 */
    private fun encode(sources: List<LegadoBookSource>): String {
        val sw = StringWriter()
        val writer = JsonWriter(sw)
        writer.beginArray()
        for (source in sources) {
            writer.beginObject()
            writer.name("bookSourceName").value(source.name)
            writer.name("bookSourceUrl").value(source.url)
            writer.name("bookSourceType").value(source.type.code)
            if (!source.group.isNullOrEmpty()) writer.name("bookSourceGroup").value(source.group)
            if (!source.comment.isNullOrEmpty()) writer.name("bookSourceComment").value(source.comment)
            writer.name("enabled").value(source.enabled)
            writer.name("enabledExplore").value(source.enabledExplore)
            if (!source.header.isNullOrEmpty()) writer.name("header").value(source.header)
            if (!source.loginUrl.isNullOrEmpty()) writer.name("loginUrl").value(source.loginUrl)
            if (!source.searchUrl.isNullOrEmpty()) writer.name("searchUrl").value(source.searchUrl)
            if (!source.exploreUrl.isNullOrEmpty()) writer.name("exploreUrl").value(source.exploreUrl)
            if (!source.bookUrlPattern.isNullOrEmpty()) writer.name("bookUrlPattern").value(source.bookUrlPattern)
            if (!source.concurrentRate.isNullOrEmpty()) writer.name("concurrentRate").value(source.concurrentRate)

            encodeRuleSearch(writer, source.ruleSearch)
            encodeRuleBookInfo(writer, source.ruleBookInfo)
            encodeRuleToc(writer, source.ruleToc)
            encodeRuleContent(writer, source.ruleContent)

            writer.endObject()
        }
        writer.endArray()
        writer.close()
        return sw.toString()
    }

    private fun encodeRuleSearch(writer: JsonWriter, rule: RuleSearch) {
        writer.name("ruleSearch").beginObject()
        if (rule.bookList.isNotEmpty()) writer.name("bookList").value(rule.bookList)
        if (rule.name.isNotEmpty()) writer.name("name").value(rule.name)
        if (rule.author.isNotEmpty()) writer.name("author").value(rule.author)
        if (rule.kind.isNotEmpty()) writer.name("kind").value(rule.kind)
        if (rule.coverUrl.isNotEmpty()) writer.name("coverUrl").value(rule.coverUrl)
        if (rule.intro.isNotEmpty()) writer.name("intro").value(rule.intro)
        if (rule.wordCount.isNotEmpty()) writer.name("wordCount").value(rule.wordCount)
        if (rule.lastChapter.isNotEmpty()) writer.name("lastChapter").value(rule.lastChapter)
        if (rule.bookUrl.isNotEmpty()) writer.name("bookUrl").value(rule.bookUrl)
        if (rule.checkKeyWord.isNotEmpty()) writer.name("checkKeyWord").value(rule.checkKeyWord)
        writer.endObject()
    }

    private fun encodeRuleBookInfo(writer: JsonWriter, rule: RuleBookInfo) {
        writer.name("ruleBookInfo").beginObject()
        if (rule.init.isNotEmpty()) writer.name("init").value(rule.init)
        if (rule.name.isNotEmpty()) writer.name("name").value(rule.name)
        if (rule.author.isNotEmpty()) writer.name("author").value(rule.author)
        if (rule.kind.isNotEmpty()) writer.name("kind").value(rule.kind)
        if (rule.intro.isNotEmpty()) writer.name("intro").value(rule.intro)
        if (rule.coverUrl.isNotEmpty()) writer.name("coverUrl").value(rule.coverUrl)
        if (rule.tocUrl.isNotEmpty()) writer.name("tocUrl").value(rule.tocUrl)
        if (rule.wordCount.isNotEmpty()) writer.name("wordCount").value(rule.wordCount)
        if (rule.lastChapter.isNotEmpty()) writer.name("lastChapter").value(rule.lastChapter)
        if (rule.canReName.isNotEmpty()) writer.name("canReName").value(rule.canReName)
        writer.endObject()
    }

    private fun encodeRuleToc(writer: JsonWriter, rule: RuleToc) {
        writer.name("ruleToc").beginObject()
        if (rule.chapterList.isNotEmpty()) writer.name("chapterList").value(rule.chapterList)
        if (rule.chapterName.isNotEmpty()) writer.name("chapterName").value(rule.chapterName)
        if (rule.chapterUrl.isNotEmpty()) writer.name("chapterUrl").value(rule.chapterUrl)
        if (rule.updateTime.isNotEmpty()) writer.name("updateTime").value(rule.updateTime)
        if (rule.isVip.isNotEmpty()) writer.name("isVip").value(rule.isVip)
        if (rule.isVolume.isNotEmpty()) writer.name("isVolume").value(rule.isVolume)
        if (rule.nextTocUrl.isNotEmpty()) writer.name("nextTocUrl").value(rule.nextTocUrl)
        writer.endObject()
    }

    private fun encodeRuleContent(writer: JsonWriter, rule: RuleContent) {
        writer.name("ruleContent").beginObject()
        if (rule.content.isNotEmpty()) writer.name("content").value(rule.content)
        if (rule.nextContentUrl.isNotEmpty()) writer.name("nextContentUrl").value(rule.nextContentUrl)
        if (rule.replaceRegex.isNotEmpty()) writer.name("replaceRegex").value(rule.replaceRegex)
        if (rule.imageStyle.isNotEmpty()) writer.name("imageStyle").value(rule.imageStyle)
        if (rule.sourceRegex.isNotEmpty()) writer.name("sourceRegex").value(rule.sourceRegex)
        if (rule.webJs.isNotEmpty()) writer.name("webJs").value(rule.webJs)
        if (rule.title.isNotEmpty()) writer.name("title").value(rule.title)
        writer.endObject()
    }

    private fun JsonReader.nextStringOrNull(): String? {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return null
        }
        return nextString()
    }

    private fun JsonReader.nextSafeString(): String {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return ""
        }
        return nextString()
    }

    private fun JsonReader.nextBooleanOrDefault(default: Boolean): Boolean {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return default
        }
        return runCatching { nextBoolean() }.getOrDefault(default)
    }

    private fun JsonReader.nextIntOrDefault(default: Int): Int {
        if (peek() == JsonToken.NULL) {
            nextNull()
            return default
        }
        return runCatching { nextInt() }.getOrDefault(default)
    }

    private companion object {
        val KEY_SOURCES = stringPreferencesKey("sources")
    }
}
