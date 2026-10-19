package com.xyreader.booksource

import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** 搜索结果里的一本书（尚未入库，只有来源侧信息） */
data class SourceBook(
    val name: String,
    val bookUrl: String,
    val sourceUrl: String,
    val sourceName: String,
    val author: String? = null,
    val kind: String? = null,
    val coverUrl: String? = null,
    val intro: String? = null,
    val lastChapter: String? = null,
)

/** 单个源的搜索结局：成功带书单，失败带原因（一个源坏了不影响其他源） */
data class SourceSearchResult(
    val sourceName: String,
    val sourceUrl: String,
    val books: List<SourceBook> = emptyList(),
    val error: String? = null,
)

/** 取页面文本；抽成接口便于测试注入假实现（测试不联网） */
interface SourcePageFetcher {
    @Throws(IOException::class)
    suspend fun fetch(url: String, headers: Map<String, String>): String

    /**
     * 同步版本：专供 JS 的 `java.ajax`（Rhino 在同步上下文里执行，无法 await）。
     * 调用方必须在 IO 线程上跑——`search()` 已用 `withContext(Dispatchers.IO)` 保证。
     */
    fun fetchBlocking(url: String, headers: Map<String, String>): String = runBlocking { fetch(url, headers) }
}

/** 生产实现：OkHttp + 每个源自己的 header（多为 UA / Referer） */
class OkHttpSourcePageFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : SourcePageFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(url)
            headers.forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value) }
            client.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
}

/**
 * 多源搜索。
 *
 * 行为约定：
 * - 只搜「启用 + 可搜索（有 searchUrl 与 bookList 规则）」的源；其余跳过；
 * - 单源失败（超时/HTTP 错/规则不匹配）只记错误，不打断其他源；
 * - 结果条目缺书名或缺书链接即丢弃（半截数据不入结果）；
 * - 规则里出现本阶段不支持的写法（`@js:` 等）时，该源大概率返回空 —— 这是已知覆盖边界，不假装成功。
 */
/** 单次取页面的结局：要么有 HTML，要么有失败原因 */
private data class PageOutcome(val html: String?, val failure: String?)

class LegadoSearchService(
    private val fetcher: SourcePageFetcher = OkHttpSourcePageFetcher(),
    private val perSourceTimeoutMs: Long = 15_000L,
) {

    suspend fun search(
        sources: List<LegadoBookSource>,
        query: String,
    ): List<SourceSearchResult> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()
        // 抓页面与执行 JS 规则都是阻塞活，整体切到 IO（UI 在主线程调用本方法）
        return withContext(Dispatchers.IO) {
            sources
                .filter { it.enabled && it.canSearch }
                .map { source -> searchOne(source, keyword) }
        }
    }

    private suspend fun searchOne(source: LegadoBookSource, keyword: String): SourceSearchResult {
        val searchUrl = source.searchUrl.orEmpty()
        val templateEvaluator = LegadoRuleEvaluator(
            mapOf(
                "key" to URLEncoder.encode(keyword, "UTF-8"),
                "query" to keyword,
                "page" to "1",
            ),
        )
        val url = templateEvaluator.expand(searchUrl)
        val headers = parseHeaders(source.header)
        val outcome = withTimeoutOrNull(perSourceTimeoutMs) {
            runCatching { fetcher.fetch(url, headers) }.fold(
                onSuccess = { html -> PageOutcome(html, null) },
                onFailure = { error -> PageOutcome(null, error.message ?: "取页面失败") },
            )
        } ?: return SourceSearchResult(source.name, source.url, error = "搜索超时或失败")

        val html = outcome.html
            ?: return SourceSearchResult(source.name, source.url, error = outcome.failure)

        val root = templateEvaluator.parseHtml(html, baseUri = source.url)
        // 页面解析之后再挂 JS：java.getString 才能在「当前页面」上求值
        val evaluator = templateEvaluator.withJs(
            LegadoJsRuntime(
                PageJsBridge(
                    root = root,
                    base = templateEvaluator,
                    baseUrl = source.url,
                    sourceName = source.name,
                    fetch = { target -> runCatching { fetcher.fetchBlocking(target, headers) }.getOrDefault("") },
                ),
            ),
        )
        val bookListRule = LegadoRule.parse(source.ruleSearch.bookList)
        val nodes = evaluator.elements(bookListRule, root)
        if (nodes.isEmpty()) {
            return SourceSearchResult(source.name, source.url, error = "没有匹配到搜索结果（规则或页面结构可能已变）")
        }

        val books = ArrayList<SourceBook>(nodes.size)
        nodes.forEach { node ->
            val scoped = RuleValue.HtmlNodes(listOf(node))
            val name = evaluator.firstString(LegadoRule.parse(source.ruleSearch.name), scoped)
            val bookUrl = evaluator.firstString(LegadoRule.parse(source.ruleSearch.bookUrl), scoped)
            if (name.isNullOrBlank() || bookUrl.isNullOrBlank()) return@forEach
            books.add(
                SourceBook(
                    name = name,
                    bookUrl = absolutize(bookUrl, source.url),
                    sourceUrl = source.url,
                    sourceName = source.name,
                    author = evaluator.firstString(LegadoRule.parse(source.ruleSearch.author), scoped),
                    kind = evaluator.firstString(LegadoRule.parse(source.ruleSearch.kind), scoped),
                    coverUrl = evaluator.firstString(LegadoRule.parse(source.ruleSearch.coverUrl), scoped)
                        ?.let { absolutize(it, source.url) },
                    intro = evaluator.firstString(LegadoRule.parse(source.ruleSearch.intro), scoped),
                    lastChapter = evaluator.firstString(LegadoRule.parse(source.ruleSearch.lastChapter), scoped),
                ),
            )
        }
        return SourceSearchResult(source.name, source.url, books = books)
    }

    /** 源自己的 header 是 JSON 字符串（Legado 约定）；解析失败就当没有 */
    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }

    /** 相对链接补全成绝对地址（结果要交给下载/详情链路，必须能直接请求） */
    private fun absolutize(url: String, base: String): String {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        val origin = base.substringBefore("://").let { scheme ->
            val host = base.substringAfter("://").substringBefore('/')
            "$scheme://$host"
        }
        return if (url.startsWith("/")) "$origin$url" else "$origin/$url"
    }
}
