package com.xyreader.booksource

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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

    @Throws(IOException::class)
    suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String? = null,
        charset: String? = null,
    ): String = fetch(url, headers)

    /**
     * 同步版本：专供 JS 的 `java.ajax`（Rhino 在同步上下文里执行，无法 await）。
     * 调用方必须在 IO 线程上跑——`search()` 已用 `withContext(Dispatchers.IO)` 保证。
     */
    fun fetchBlocking(url: String, headers: Map<String, String>): String = runBlocking { fetch(url, headers) }
}

/** 生产实现：OkHttp + 每个源自己的 header（多为 UA / Referer），支持 GET/POST 与字符集自动识别 */
class OkHttpSourcePageFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build(),
) : SourcePageFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>): String =
        fetch(url, headers, method = "GET", body = null, charset = null)

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String?,
        charset: String?,
    ): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        headers.forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value) }

        val reqCharset = resolveCharset(charset)
        if (method.equals("POST", ignoreCase = true)) {
            val reqBody = body.orEmpty()
            val mediaType = if (reqBody.trimStart().startsWith("{") || reqBody.trimStart().startsWith("[")) {
                "application/json; charset=${reqCharset.name()}".toMediaType()
            } else {
                "application/x-www-form-urlencoded; charset=${reqCharset.name()}".toMediaType()
            }
            builder.post(reqBody.toByteArray(reqCharset).toRequestBody(mediaType))
        } else {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: return@withContext ""
            val respCharset = resolveResponseCharset(response, bytes, charset)
            String(bytes, respCharset)
        }
    }

    private fun resolveCharset(charset: String?): Charset {
        if (!charset.isNullOrBlank()) {
            runCatching { return Charset.forName(charset) }
        }
        return Charsets.UTF_8
    }

    private fun resolveResponseCharset(response: Response, bytes: ByteArray, explicitCharset: String?): Charset {
        if (!explicitCharset.isNullOrBlank() && !explicitCharset.equals("utf-8", ignoreCase = true) && !explicitCharset.equals("utf8", ignoreCase = true)) {
            runCatching { return Charset.forName(explicitCharset) }
        }
        response.body?.contentType()?.charset()?.let { return it }
        val snippet = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        CHARSET_REGEX.find(snippet)?.groupValues?.get(1)?.let { found ->
            runCatching { return Charset.forName(found) }
        }
        if (!explicitCharset.isNullOrBlank()) {
            runCatching { return Charset.forName(explicitCharset) }
        }
        return Charsets.UTF_8
    }

    companion object {
        private val CHARSET_REGEX = Regex("""(?i)charset\s*=\s*['"]?([a-zA-Z0-9_\-]+)""")
    }
}

/** 单次取页面的结局：要么有 HTML，要么有失败原因 */
private data class PageOutcome(val html: String?, val failure: String?)

class LegadoSearchService(
    private val fetcher: SourcePageFetcher = OkHttpSourcePageFetcher(),
    private val perSourceTimeoutMs: Long = 15_000L,
) {

    /**
     * 渐进式多源搜索：逐个源搜索，通过回调报告进度与单个源结果；
     * 支持协程取消（点「停止搜索」时立即响应，已搜到的结果不丢失）。
     */
    suspend fun searchProgressive(
        sources: List<LegadoBookSource>,
        query: String,
        onSourceStart: (suspend (source: LegadoBookSource, index: Int, total: Int) -> Unit)? = null,
        onSourceResult: (suspend (source: LegadoBookSource, result: SourceSearchResult, index: Int, total: Int) -> Unit)? = null,
    ): List<SourceSearchResult> {
        val keyword = query.trim()
        if (keyword.isEmpty()) return emptyList()
        val targets = sources.filter { it.enabled && it.canSearch }
        if (targets.isEmpty()) return emptyList()
        // 抓页面与执行 JS 规则都是阻塞活，整体切到 IO（UI 在主线程调用本方法）
        return withContext(Dispatchers.IO) {
            val results = ArrayList<SourceSearchResult>(targets.size)
            for ((index, source) in targets.withIndex()) {
                coroutineContext.ensureActive()
                onSourceStart?.invoke(source, index, targets.size)
                val result = searchOne(source, keyword)
                results.add(result)
                onSourceResult?.invoke(source, result, index + 1, targets.size)
            }
            results
        }
    }

    suspend fun search(
        sources: List<LegadoBookSource>,
        query: String,
    ): List<SourceSearchResult> = searchProgressive(sources, query)

    private data class ParsedSearchUrl(
        val url: String,
        val method: String = "GET",
        val body: String? = null,
        val charset: String = "UTF-8",
        val headers: Map<String, String> = emptyMap(),
    )

    private fun parseSearchUrl(raw: String): ParsedSearchUrl {
        val trimmed = raw.trim()
        val commaIndex = trimmed.indexOf(',')
        if (commaIndex != -1) {
            val candidateUrl = trimmed.substring(0, commaIndex).trim()
            val candidateOptions = trimmed.substring(commaIndex + 1).trim()
            if (candidateOptions.startsWith("{") && candidateOptions.endsWith("}")) {
                val json = runCatching { JSONObject(candidateOptions) }.getOrNull()
                if (json != null) {
                    val method = json.optString("method", "GET").ifBlank { "GET" }.uppercase()
                    val charset = json.optString("charset").ifBlank { "UTF-8" }
                    val body = json.optString("body").takeIf { it.isNotBlank() }
                    val extraHeaders = mutableMapOf<String, String>()
                    val headersObj = json.optJSONObject("headers")
                    headersObj?.keys()?.forEach { k ->
                        extraHeaders[k] = headersObj.optString(k)
                    }
                    return ParsedSearchUrl(
                        url = candidateUrl,
                        method = method,
                        body = body,
                        charset = charset,
                        headers = extraHeaders,
                    )
                }
            }
        }
        return ParsedSearchUrl(url = trimmed)
    }

    private suspend fun searchOne(source: LegadoBookSource, keyword: String): SourceSearchResult {
        val rawSearchUrl = source.searchUrl.orEmpty()
        val parsed = parseSearchUrl(rawSearchUrl)
        val encodedKey = runCatching { URLEncoder.encode(keyword, parsed.charset) }
            .getOrDefault(URLEncoder.encode(keyword, "UTF-8"))

        val templateEvaluator = LegadoRuleEvaluator(
            mapOf(
                "key" to encodedKey,
                "searchKey" to encodedKey,
                "query" to keyword,
                "page" to "1",
                "source.key" to source.url,
                "source.bookSourceUrl" to source.url,
                "baseUrl" to source.url,
            ),
        )
        val expandedUrl = templateEvaluator.expand(parsed.url)
        val url = absolutize(expandedUrl, source.url)
        val expandedBody = parsed.body?.let { templateEvaluator.expand(it) }
        val headers = parseHeaders(source.header) + parsed.headers

        val outcome = withTimeoutOrNull(perSourceTimeoutMs) {
            try {
                val html = fetcher.fetch(
                    url = url,
                    headers = headers,
                    method = parsed.method,
                    body = expandedBody,
                    charset = parsed.charset,
                )
                PageOutcome(html, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageOutcome(null, e.message ?: "取页面失败")
            }
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

        val rawBooks = ArrayList<SourceBook>(nodes.size)
        nodes.forEach { node ->
            val scoped = RuleValue.HtmlNodes(listOf(node))
            val name = evaluator.firstString(LegadoRule.parse(source.ruleSearch.name), scoped)?.trim()
            val bookUrl = evaluator.firstString(LegadoRule.parse(source.ruleSearch.bookUrl), scoped)?.trim()
            if (name.isNullOrBlank() || bookUrl.isNullOrBlank()) return@forEach
            rawBooks.add(
                SourceBook(
                    name = name,
                    bookUrl = absolutize(bookUrl, source.url),
                    sourceUrl = source.url,
                    sourceName = source.name,
                    author = evaluator.firstString(LegadoRule.parse(source.ruleSearch.author), scoped)?.trim(),
                    kind = evaluator.firstString(LegadoRule.parse(source.ruleSearch.kind), scoped)?.trim(),
                    coverUrl = evaluator.firstString(LegadoRule.parse(source.ruleSearch.coverUrl), scoped)?.trim()
                        ?.let { absolutize(it, source.url) },
                    intro = evaluator.firstString(LegadoRule.parse(source.ruleSearch.intro), scoped)?.trim(),
                    lastChapter = evaluator.firstString(LegadoRule.parse(source.ruleSearch.lastChapter), scoped)?.trim(),
                ),
            )
        }

        if (rawBooks.isEmpty()) {
            return SourceSearchResult(source.name, source.url, error = "没有匹配到搜索结果（条目缺少书名或链接）")
        }

        // 关键词相关性过滤：将完全无关的书籍（如页面推荐、排行榜夹带）滤除
        val searchTerms = keyword.split("\\s+".toRegex()).filter { it.isNotBlank() }
        val matchedBooks = rawBooks.filter { book ->
            book.name.contains(keyword, ignoreCase = true) ||
            book.author?.contains(keyword, ignoreCase = true) == true ||
            book.intro?.contains(keyword, ignoreCase = true) == true ||
            searchTerms.any { term ->
                book.name.contains(term, ignoreCase = true) ||
                book.author?.contains(term, ignoreCase = true) == true ||
                book.intro?.contains(term, ignoreCase = true) == true
            }
        }
        val finalBooks = if (matchedBooks.isNotEmpty()) matchedBooks else rawBooks

        val sortedBooks = finalBooks.sortedWith(
            compareByDescending<SourceBook> { it.name.equals(keyword, ignoreCase = true) }
                .thenByDescending { it.name.contains(keyword, ignoreCase = true) }
                .thenByDescending { it.author?.contains(keyword, ignoreCase = true) == true }
        )

        return SourceSearchResult(source.name, source.url, books = sortedBooks)
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
        if (url.startsWith("//")) {
            val scheme = if (base.contains("://")) base.substringBefore("://") else "https"
            return "$scheme:$url"
        }
        val origin = base.substringBefore("://").let { scheme ->
            val host = base.substringAfter("://").substringBefore('/')
            "$scheme://$host"
        }
        return if (url.startsWith("/")) "$origin$url" else "$origin/$url"
    }
}
