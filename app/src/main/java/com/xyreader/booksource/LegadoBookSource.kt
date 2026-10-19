package com.xyreader.booksource

import org.json.JSONArray
import org.json.JSONObject

/** 书源类型（对应 Legado 的 bookSourceType） */
enum class BookSourceType(val code: Int) {
    NOVEL(0),
    AUDIO(1),
    COMIC(2),
    FILE(3),
    UNKNOWN(-1),
    ;

    companion object {
        fun fromCode(code: Int): BookSourceType = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** 搜索规则（Legado ruleSearch 子字段，实测 2664 条源确认） */
data class RuleSearch(
    val bookList: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    val coverUrl: String = "",
    val intro: String = "",
    val wordCount: String = "",
    val lastChapter: String = "",
    val bookUrl: String = "",
    val checkKeyWord: String = "",
)

/** 详情规则 */
data class RuleBookInfo(
    val init: String = "",
    val name: String = "",
    val author: String = "",
    val kind: String = "",
    val intro: String = "",
    val coverUrl: String = "",
    val tocUrl: String = "",
    val wordCount: String = "",
    val lastChapter: String = "",
    val canReName: String = "",
)

/** 目录规则 */
data class RuleToc(
    val chapterList: String = "",
    val chapterName: String = "",
    val chapterUrl: String = "",
    val updateTime: String = "",
    val isVip: String = "",
    val isVolume: String = "",
    val nextTocUrl: String = "",
)

/** 正文规则 */
data class RuleContent(
    val content: String = "",
    val nextContentUrl: String = "",
    val replaceRegex: String = "",
    val imageStyle: String = "",
    val sourceRegex: String = "",
    val webJs: String = "",
    val title: String = "",
)

/**
 * 用户导入的一条在线书源。
 *
 * 字段名与 Legado 3.0 保持一致（取自真实源的实测字段，见
 * `docs/superpowers/plans/2026-10-17-legado-source-engine.md`）。
 * 未建模的字段（`jsLib`、`exploreScreen` 等）留在 [raw] 里，避免因格式演进而丢数据。
 *
 * 注意：仓库不携带任何源数据；本类只描述结构。
 */
data class LegadoBookSource(
    val name: String,
    val url: String,
    val type: BookSourceType = BookSourceType.NOVEL,
    val group: String? = null,
    val comment: String? = null,
    val enabled: Boolean = true,
    val enabledExplore: Boolean = true,
    val header: String? = null,
    val loginUrl: String? = null,
    val searchUrl: String? = null,
    val exploreUrl: String? = null,
    val bookUrlPattern: String? = null,
    val concurrentRate: String? = null,
    val ruleSearch: RuleSearch = RuleSearch(),
    val ruleBookInfo: RuleBookInfo = RuleBookInfo(),
    val ruleToc: RuleToc = RuleToc(),
    val ruleContent: RuleContent = RuleContent(),
    val raw: JSONObject? = null,
) {
    /** 是否可用于搜索（没有 searchUrl 或没有搜索规则时只能浏览/直接打开） */
    val canSearch: Boolean get() = !searchUrl.isNullOrBlank() && ruleSearch.bookList.isNotBlank()

    /** 需要登录才能用（本轮不支持登录源，导入时应向用户说明） */
    val requiresLogin: Boolean get() = !loginUrl.isNullOrBlank()

    /** 源标识：来源 ID 用「域名」而不是整个 URL，避免路径变化导致下载身份漂移 */
    val hostKey: String
        get() = runCatching {
            val withoutScheme = url.substringAfter("://", url)
            withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        }.getOrDefault(url.lowercase())

    companion object {

        /**
         * 解析用户导入的内容：支持数组（多条）与单对象（一条）。
         *
         * 只做结构解析：非法条目跳过而不抛异常，保证一条坏源不会影响整批导入。
         */
        fun parseImport(text: String): List<LegadoBookSource> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            return runCatching {
                when {
                    trimmed.startsWith("[") -> {
                        val array = JSONArray(trimmed)
                        (0 until array.length()).mapNotNull { index ->
                            array.optJSONObject(index)?.let { runCatching { fromJson(it) }.getOrNull() }
                        }
                    }
                    trimmed.startsWith("{") -> listOf(fromJson(JSONObject(trimmed)))
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
        }

        fun fromJson(json: JSONObject): LegadoBookSource {
            val name = json.optString("bookSourceName").trim()
            val url = json.optString("bookSourceUrl").trim()
            require(name.isNotEmpty()) { "书源缺少 bookSourceName" }
            require(url.isNotEmpty()) { "书源缺少 bookSourceUrl" }
            val search = json.optJSONObject("ruleSearch")
            val info = json.optJSONObject("ruleBookInfo")
            val toc = json.optJSONObject("ruleToc")
            val content = json.optJSONObject("ruleContent")
            return LegadoBookSource(
                name = name,
                url = url,
                type = BookSourceType.fromCode(json.optInt("bookSourceType", 0)),
                group = json.optString("bookSourceGroup").takeIf { it.isNotBlank() },
                comment = json.optString("bookSourceComment").takeIf { it.isNotBlank() },
                // enabled 缺省为 true：多数源不写这个字段，缺失不等于禁用
                enabled = if (json.has("enabled")) json.optBoolean("enabled", true) else true,
                enabledExplore = if (json.has("enabledExplore")) json.optBoolean("enabledExplore", true) else true,
                header = json.optString("header").takeIf { it.isNotBlank() },
                loginUrl = json.optString("loginUrl").takeIf { it.isNotBlank() },
                searchUrl = json.optString("searchUrl").takeIf { it.isNotBlank() },
                exploreUrl = json.optString("exploreUrl").takeIf { it.isNotBlank() },
                bookUrlPattern = json.optString("bookUrlPattern").takeIf { it.isNotBlank() },
                concurrentRate = json.optString("concurrentRate").takeIf { it.isNotBlank() },
                ruleSearch = RuleSearch(
                    bookList = search.field("bookList"),
                    name = search.field("name"),
                    author = search.field("author"),
                    kind = search.field("kind"),
                    coverUrl = search.field("coverUrl"),
                    intro = search.field("intro"),
                    wordCount = search.field("wordCount"),
                    lastChapter = search.field("lastChapter"),
                    bookUrl = search.field("bookUrl"),
                    checkKeyWord = search.field("checkKeyWord"),
                ),
                ruleBookInfo = RuleBookInfo(
                    init = info.field("init"),
                    name = info.field("name"),
                    author = info.field("author"),
                    kind = info.field("kind"),
                    intro = info.field("intro"),
                    coverUrl = info.field("coverUrl"),
                    tocUrl = info.field("tocUrl"),
                    wordCount = info.field("wordCount"),
                    lastChapter = info.field("lastChapter"),
                    canReName = info.field("canReName"),
                ),
                ruleToc = RuleToc(
                    chapterList = toc.field("chapterList"),
                    chapterName = toc.field("chapterName"),
                    chapterUrl = toc.field("chapterUrl"),
                    updateTime = toc.field("updateTime"),
                    isVip = toc.field("isVip"),
                    isVolume = toc.field("isVolume"),
                    nextTocUrl = toc.field("nextTocUrl"),
                ),
                ruleContent = RuleContent(
                    content = content.field("content"),
                    nextContentUrl = content.field("nextContentUrl"),
                    replaceRegex = content.field("replaceRegex"),
                    imageStyle = content.field("imageStyle"),
                    sourceRegex = content.field("sourceRegex"),
                    webJs = content.field("webJs"),
                    title = content.field("title"),
                ),
                raw = json,
            )
        }

        /** 规则组缺字段时统一取空串，避免后续判空分支遍地 */
        private fun JSONObject?.field(key: String): String =
            this?.optString(key)?.trim().orEmpty()
    }
}
