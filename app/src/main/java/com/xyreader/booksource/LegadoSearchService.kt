package com.xyreader.booksource

import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/** HTTP 响应结果 */
data class FetchResponse(
    val body: String,
    val finalUrl: String,
    val statusCode: Int = 200,
)

/** 取页面文本；抽成接口便于测试注入假实现（测试不联网） */
interface SourcePageFetcher {
    @Throws(IOException::class)
    suspend fun fetch(
        url: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
        charset: String? = null,
    ): FetchResponse = FetchResponse(body = fetch(url, headers), finalUrl = url)

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
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build(),
) : SourcePageFetcher {

    override suspend fun fetch(
        url: String,
        headers: Map<String, String>,
        method: String,
        body: String?,
        charset: String?,
    ): FetchResponse = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (!headers.containsKey("User-Agent") && !headers.containsKey("user-agent")) {
            builder.header("User-Agent", BookSourceImporter.DEFAULT_USER_AGENT)
        }
        headers.forEach { (key, value) -> if (key.isNotBlank() && value.isNotBlank()) builder.header(key, value) }

        if (method.equals("POST", ignoreCase = true)) {
            val mediaType = "application/x-www-form-urlencoded; charset=${charset ?: "UTF-8"}".toMediaTypeOrNull()
            builder.post((body ?: "").toRequestBody(mediaType))
        } else {
            builder.get()
        }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val bytes = response.body?.bytes() ?: ByteArray(0)
            val decoded = decodeHtmlBytes(bytes, response.header("Content-Type"), charset)
            val finalUrl = response.request.url.toString()
            FetchResponse(body = decoded, finalUrl = finalUrl, statusCode = response.code)
        }
    }

    override suspend fun fetch(url: String, headers: Map<String, String>): String =
        fetch(url, headers, "GET", null, null).body
}

/** 单次取页面的结局：要么有 HTML 与最终 URL，要么有失败原因 */
private data class PageOutcome(val html: String?, val finalUrl: String?, val failure: String?)

/**
 * 多源并发搜索服务。
 *
 * 优化点：
 * - 并发搜索（默认 concurrency = 10），不再单线程串行等待；
 * - 相对路径自动补全基准 URL；
 * - 支持 Legado searchUrl 扩展参数（POST 方法、body 传参、charset 编码、headers）；
 * - 编码支持：自动识别/指定 GBK / UTF-8，彻底解决中文小说站搜索无结果及乱码问题；
 * - 支持 JSON 结构搜索结果解析；
 * - 支持搜索直达详情页（Direct Match / checkKeyWord）提取单本。
 */
class LegadoSearchService(
    private val fetcher: SourcePageFetcher = OkHttpSourcePageFetcher(),
    private val perSourceTimeoutMs: Long = 10_000L,
    private val concurrency: Int = 10,
) {

    /**
     * 渐进式多源并发搜索：多源并发发起，通过互斥锁保证进度与单条结果回调顺序安全；
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

        return withContext(Dispatchers.IO) {
            val channel = Channel<IndexedValue<LegadoBookSource>>(Channel.UNLIMITED)
            for (iv in targets.withIndex()) {
                channel.send(iv)
            }
            channel.close()

            val doneCounter = AtomicInteger(0)
            val callbackMutex = Mutex()
            val allResults = ConcurrentLinkedQueue<SourceSearchResult>()

            val workerCount = minOf(concurrency, targets.size)
            supervisorScope {
                val workers = List(workerCount) {
                    launch {
                        for (iv in channel) {
                            coroutineContext.ensureActive()
                            val source = iv.value
                            val index = iv.index
                            callbackMutex.withLock {
                                onSourceStart?.invoke(source, index, targets.size)
                            }
                            val result = try {
                                searchOne(source, keyword)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                SourceSearchResult(source.name, source.url, error = e.message ?: "搜索失败")
                            }
                            allResults.add(result)
                            callbackMutex.withLock {
                                val done = doneCounter.incrementAndGet()
                                onSourceResult?.invoke(source, result, done, targets.size)
                            }
                        }
                    }
                }
                workers.joinAll()
            }
            allResults.toList()
        }
    }

    suspend fun search(
        sources: List<LegadoBookSource>,
        query: String,
    ): List<SourceSearchResult> = searchProgressive(sources, query)

    private suspend fun searchOne(source: LegadoBookSource, keyword: String): SourceSearchResult {
        val rawSearchUrl = source.searchUrl.orEmpty()
        val config = parseSearchRequestConfig(rawSearchUrl, source.url)
        val charset = config.charset
        val encodedKey = runCatching {
            if (!charset.isNullOrBlank()) URLEncoder.encode(keyword, charset)
            else URLEncoder.encode(keyword, "UTF-8")
        }.getOrDefault(URLEncoder.encode(keyword, "UTF-8"))

        val templateEvaluator = LegadoRuleEvaluator(
            mapOf(
                "key" to encodedKey,
                "query" to keyword,
                "page" to "1",
            ),
        )
        val finalReqUrl = templateEvaluator.expand(config.url)
        val finalReqBody = config.body?.let { templateEvaluator.expand(it) }
        val sourceHeaders = parseHeaders(source.header)
        val allHeaders = sourceHeaders + config.headers

        val outcome = withTimeoutOrNull(perSourceTimeoutMs) {
            try {
                val resp = fetcher.fetch(
                    url = finalReqUrl,
                    headers = allHeaders,
                    method = config.method,
                    body = finalReqBody,
                    charset = charset,
                )
                PageOutcome(resp.body, resp.finalUrl, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PageOutcome(null, null, e.message ?: "取页面失败")
            }
        } ?: return SourceSearchResult(source.name, source.url, error = "搜索超时或失败")

        val html = outcome.html
            ?: return SourceSearchResult(source.name, source.url, error = outcome.failure)
        val finalUrl = outcome.finalUrl ?: finalReqUrl

        // 优先检查 JSON 结构搜索结果
        val trimmed = html.trim()
        val isJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        if (isJson) {
            val jsonRoot = templateEvaluator.parseJson(html)
            val bookListRule = LegadoRule.parse(source.ruleSearch.bookList)
            val listResult = templateEvaluator.evaluate(bookListRule, jsonRoot)
            val jsonItems = (listResult as? RuleValue.Texts)?.values.orEmpty()
            if (jsonItems.isNotEmpty()) {
                val books = ArrayList<SourceBook>(jsonItems.size)
                for (itemStr in jsonItems) {
                    val scoped = templateEvaluator.parseJson(itemStr)
                    val name = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.name), scoped)
                    val bookUrl = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.bookUrl), scoped)
                    if (name.isNullOrBlank() || bookUrl.isNullOrBlank()) continue
                    books.add(
                        SourceBook(
                            name = name,
                            bookUrl = absolutize(bookUrl, source.url),
                            sourceUrl = source.url,
                            sourceName = source.name,
                            author = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.author), scoped),
                            kind = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.kind), scoped),
                            coverUrl = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.coverUrl), scoped)
                                ?.let { absolutize(it, source.url) },
                            intro = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.intro), scoped),
                            lastChapter = templateEvaluator.firstString(LegadoRule.parse(source.ruleSearch.lastChapter), scoped),
                        ),
                    )
                }
                if (books.isNotEmpty()) {
                    val finalBooks = filterAndSortBooks(books, keyword)
                    return SourceSearchResult(source.name, source.url, books = finalBooks)
                }
            }
        }

        // HTML 页面解析
        val root = templateEvaluator.parseHtml(html, baseUri = source.url)
        val evaluator = templateEvaluator.withJs(
            LegadoJsRuntime(
                PageJsBridge(
                    root = root,
                    base = templateEvaluator,
                    baseUrl = source.url,
                    sourceName = source.name,
                    fetch = { target -> runCatching { fetcher.fetchBlocking(target, allHeaders) }.getOrDefault("") },
                ),
            ),
        )

        val bookListRule = LegadoRule.parse(source.ruleSearch.bookList)
        val nodes = evaluator.elements(bookListRule, root)
        if (nodes.isEmpty()) {
            // 命中直达书籍详情页（302 重定向或精准命中）
            val directBook = tryExtractDirectDetailBook(source, keyword, root, evaluator, finalUrl)
            if (directBook != null) {
                return SourceSearchResult(source.name, source.url, books = listOf(directBook))
            }
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
        val finalBooks = filterAndSortBooks(books, keyword)
        return SourceSearchResult(source.name, source.url, books = finalBooks)
    }

    private fun isRelevant(book: SourceBook, keyword: String, terms: List<String>): Boolean {
        val cleanName = book.name.trim()
        val cleanKw = keyword.trim()
        if (cleanName.contains(cleanKw, ignoreCase = true) || cleanKw.contains(cleanName, ignoreCase = true)) return true
        if (book.author?.contains(cleanKw, ignoreCase = true) == true) return true
        if (book.intro?.contains(cleanKw, ignoreCase = true) == true) return true
        return terms.any { term ->
            cleanName.contains(term, ignoreCase = true) ||
            (term.length >= 2 && term.contains(cleanName, ignoreCase = true)) ||
            book.author?.contains(term, ignoreCase = true) == true ||
            book.intro?.contains(term, ignoreCase = true) == true
        }
    }

    private fun filterAndSortBooks(books: List<SourceBook>, keyword: String): List<SourceBook> {
        if (books.isEmpty()) return books
        val trimmed = keyword.trim()
        val terms = trimmed.split("\\s+".toRegex()).filter { it.isNotBlank() }

        val matched = books.filter { book ->
            isRelevant(book, trimmed, terms)
        }

        // 核心逻辑：如果列表里存在与搜索关键词匹配的书籍（书名、作者、简介命中了完整词或分词），
        // 则坚决过滤掉列表里掺杂的侧边栏推荐、大家都在看等无关书籍！
        // 如果一条都没匹配上（例如部分特殊搜索源或测试桩），则保留原列表，并按相关度排序兜底。
        val candidates = if (matched.isNotEmpty()) matched else books

        return candidates.sortedWith(
            compareByDescending<SourceBook> { it.name.equals(trimmed, ignoreCase = true) }
                .thenByDescending { it.name.startsWith(trimmed, ignoreCase = true) }
                .thenByDescending { it.name.contains(trimmed, ignoreCase = true) }
                .thenByDescending { it.author?.equals(trimmed, ignoreCase = true) == true }
                .thenByDescending { it.author?.contains(trimmed, ignoreCase = true) == true }
                .thenByDescending { terms.any { term -> it.name.contains(term, ignoreCase = true) } }
                .thenByDescending { terms.any { term -> it.author?.contains(term, ignoreCase = true) == true } }
                .thenByDescending { it.intro?.contains(trimmed, ignoreCase = true) == true }
        )
    }

    private fun tryExtractDirectDetailBook(
        source: LegadoBookSource,
        keyword: String,
        root: RuleValue,
        evaluator: LegadoRuleEvaluator,
        finalUrl: String,
    ): SourceBook? {
        val checkKeyWordRule = LegadoRule.parse(source.ruleSearch.checkKeyWord)
        val checkMatch = if (!checkKeyWordRule.isEmpty) {
            evaluator.firstString(checkKeyWordRule, root)
        } else null

        val directTitle = evaluator.firstString(LegadoRule.parse(source.ruleSearch.name), root)
            ?: evaluator.firstString(LegadoRule.parse(source.ruleBookInfo.name), root)

        val isDirectDetail = when {
            !checkMatch.isNullOrBlank() -> true
            !directTitle.isNullOrBlank() && (
                directTitle.contains(keyword, ignoreCase = true) ||
                keyword.contains(directTitle, ignoreCase = true)
            ) -> true
            else -> false
        }

        if (!isDirectDetail || directTitle.isNullOrBlank()) return null

        val author = evaluator.firstString(LegadoRule.parse(source.ruleSearch.author), root)
            ?: evaluator.firstString(LegadoRule.parse(source.ruleBookInfo.author), root)
        val coverUrl = evaluator.firstString(LegadoRule.parse(source.ruleSearch.coverUrl), root)
            ?: evaluator.firstString(LegadoRule.parse(source.ruleBookInfo.coverUrl), root)
        val intro = evaluator.firstString(LegadoRule.parse(source.ruleSearch.intro), root)
            ?: evaluator.firstString(LegadoRule.parse(source.ruleBookInfo.intro), root)
        val lastChapter = evaluator.firstString(LegadoRule.parse(source.ruleSearch.lastChapter), root)
            ?: evaluator.firstString(LegadoRule.parse(source.ruleBookInfo.lastChapter), root)

        return SourceBook(
            name = directTitle,
            bookUrl = absolutize(finalUrl, source.url),
            sourceUrl = source.url,
            sourceName = source.name,
            author = author,
            kind = evaluator.firstString(LegadoRule.parse(source.ruleSearch.kind), root),
            coverUrl = coverUrl?.let { absolutize(it, source.url) },
            intro = intro,
            lastChapter = lastChapter,
        )
    }

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }
}

/** 搜索请求配置 */
internal data class SearchRequestConfig(
    val url: String,
    val method: String = "GET",
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val charset: String? = null,
)

/**
 * 解析 Legado 格式的 searchUrl：
 * 支持 URL 后的 `,{"method":"POST","body":"...","charset":"gbk"}` 以及 `@header:{...}` 等配置。
 */
internal fun parseSearchRequestConfig(rawSearchUrl: String, baseUrl: String): SearchRequestConfig {
    var urlPart = rawSearchUrl.trim()
    var method = "GET"
    var body: String? = null
    var charset: String? = null
    val extraHeaders = mutableMapOf<String, String>()

    val delimiterIdx = findOptionsDelimiter(urlPart)
    if (delimiterIdx != -1) {
        val optionsStr = urlPart.substring(delimiterIdx + 1).trim()
        urlPart = urlPart.substring(0, delimiterIdx).trim()

        if (optionsStr.startsWith("header:", ignoreCase = true)) {
            val jsonStr = optionsStr.substring("header:".length).trim()
            runCatching {
                val json = JSONObject(jsonStr)
                json.keys().forEach { k -> extraHeaders[k] = json.optString(k) }
            }
        } else if (optionsStr.startsWith("{") && optionsStr.endsWith("}")) {
            runCatching {
                val json = JSONObject(optionsStr)
                if (json.has("method")) method = json.optString("method", "GET").uppercase()
                if (json.has("body")) body = json.optString("body")
                if (json.has("charset")) charset = json.optString("charset")
                if (json.has("headers")) {
                    val hObj = json.optJSONObject("headers")
                    if (hObj != null) {
                        hObj.keys().forEach { k -> extraHeaders[k] = hObj.optString(k) }
                    }
                }
            }
        }
    }

    val resolvedUrl = absolutize(urlPart, baseUrl)
    return SearchRequestConfig(
        url = resolvedUrl,
        method = method,
        body = body,
        headers = extraHeaders,
        charset = charset,
    )
}

internal fun findOptionsDelimiter(input: String): Int {
    for (i in input.indices) {
        val ch = input[i]
        if (ch == ',' || ch == '@') {
            val rest = input.substring(i + 1).trimStart()
            if (rest.startsWith("{") || rest.startsWith("header:", ignoreCase = true)) {
                return i
            }
        }
    }
    return -1
}

/** 相对链接补全成绝对地址 */
internal fun absolutize(url: String, base: String): String {
    val trimmed = url.trim()
    if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) return trimmed
    if (base.isBlank()) return trimmed
    val cleanBase = if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) {
        "http://$base"
    } else {
        base
    }
    val scheme = cleanBase.substringBefore("://")
    val host = cleanBase.substringAfter("://").substringBefore('/')
    val origin = "$scheme://$host"
    return if (trimmed.startsWith("/")) "$origin$trimmed" else "$origin/$trimmed"
}

/** 智能解码 HTML 字节数组（优先指定编码 -> Content-Type -> meta 标签 -> 兜底 UTF-8） */
internal fun decodeHtmlBytes(bytes: ByteArray, contentTypeHeader: String?, preferredCharset: String?): String {
    if (bytes.isEmpty()) return ""
    if (!preferredCharset.isNullOrBlank()) {
        val result = runCatching { String(bytes, Charset.forName(preferredCharset)) }.getOrNull()
        if (result != null) return result
    }
    if (!contentTypeHeader.isNullOrBlank()) {
        val match = Regex("charset=([a-zA-Z0-9_-]+)", RegexOption.IGNORE_CASE).find(contentTypeHeader)
        if (match != null) {
            val csName = match.groupValues[1].trim()
            val result = runCatching { String(bytes, Charset.forName(csName)) }.getOrNull()
            if (result != null) return result
        }
    }
    val sampleLen = minOf(bytes.size, 2048)
    val sample = String(bytes.sliceArray(0 until sampleLen), Charsets.ISO_8859_1)
    val metaMatch = Regex("""<meta[^>]+charset=["']?([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE).find(sample)
    if (metaMatch != null) {
        val csName = metaMatch.groupValues[1].trim()
        val result = runCatching { String(bytes, Charset.forName(csName)) }.getOrNull()
        if (result != null) return result
    }
    return String(bytes, Charsets.UTF_8)
}
