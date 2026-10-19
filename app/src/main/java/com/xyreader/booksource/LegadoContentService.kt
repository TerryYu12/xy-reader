package com.xyreader.booksource

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

/** 目录里的一章 */
data class SourceChapter(val index: Int, val name: String, val url: String)

/** 整本抓取的结局 */
sealed interface BookFetchResult {
    /** 成功：已写出可读文件（TXT），返回章节数 */
    data class Ok(val file: File, val chapterCount: Int) : BookFetchResult

    /** 失败：任何一章取不到就整体失败（不允许「半本书」入库） */
    data class Failed(val message: String) : BookFetchResult
}

/**
 * 正文链路：详情页 → 目录（含翻页）→ 逐章正文（含 `nextContentUrl` 多页拼接）→ 合成可读文件。
 *
 * 硬约定（与前期调研的落地要求一致）：
 * - **不产出半本书**：任何一章失败即整体失败（避免把抓残的书入库成"已下载"）；
 * - 单章正文有页数上限（[maxPagesPerChapter]）与目录页数上限（[maxTocPages]），防跑飞；
 * - 只做文本；`@js:` 之类的未实现规则会自然导致失败并如实报错。
 */
class LegadoContentService(
    private val fetcher: SourcePageFetcher = OkHttpSourcePageFetcher(),
    private val perPageTimeoutMs: Long = 15_000L,
    private val maxTocPages: Int = 10,
    private val maxPagesPerChapter: Int = 10,
) {

    /** 取目录（详情页 → tocUrl（可空） → chapterList，必要时按 nextTocUrl 翻页） */
    suspend fun chapters(source: LegadoBookSource, bookUrl: String): Result<List<SourceChapter>> {
        val infoRule = source.ruleBookInfo
        val detailPage = fetch(source, bookUrl) ?: return Result.failure(IOException("取详情页失败"))
        val detailBase = evaluatorFor(source)
        val detailRoot = detailBase.parseHtml(detailPage, bookUrl)
        val detailEvaluator = jsAware(detailBase, detailRoot, bookUrl, source)
        val tocPageUrl = infoRule.tocUrl.takeIf { it.isNotBlank() }
            ?.let { rule -> detailEvaluator.firstString(LegadoRule.parse(rule), detailRoot) }
            ?.let { absolutize(it, bookUrl) }
            ?: bookUrl

        val chapters = ArrayList<SourceChapter>()
        val visited = HashSet<String>()
        var pageUrl: String? = tocPageUrl
        var pages = 0
        while (pageUrl != null && pages < maxTocPages) {
            if (!visited.add(pageUrl)) break
            pages++
            val page = if (pageUrl == bookUrl) detailPage else fetch(source, pageUrl)
                ?: return Result.failure(IOException("取目录页失败：$pageUrl"))
            val baseEvaluator = evaluatorFor(source)
            val root = baseEvaluator.parseHtml(page, pageUrl)
            val evaluator = jsAware(baseEvaluator, root, pageUrl, source)
            val nodes = evaluator.elements(LegadoRule.parse(source.ruleToc.chapterList), root)
            nodes.forEach { node ->
                val scoped = RuleValue.HtmlNodes(listOf(node))
                val name = evaluator.firstString(LegadoRule.parse(source.ruleToc.chapterName), scoped)
                val url = evaluator.firstString(LegadoRule.parse(source.ruleToc.chapterUrl), scoped)
                if (!name.isNullOrBlank() && !url.isNullOrBlank()) {
                    chapters.add(SourceChapter(chapters.size, name, absolutize(url, pageUrl)))
                }
            }
            pageUrl = evaluator.firstString(LegadoRule.parse(source.ruleToc.nextTocUrl), root)
                ?.let { absolutize(it, pageUrl) }
                ?.takeIf { it.isNotBlank() }
        }
        if (chapters.isEmpty()) return Result.failure(IOException("目录为空（规则或页面结构可能已变）"))
        return Result.success(chapters)
    }

    /** 取单章正文：`content` + 按 `nextContentUrl` 拼接后续页，再做 `replaceRegex` 净化 */
    suspend fun content(source: LegadoBookSource, chapterUrl: String): Result<String> {
        val pieces = ArrayList<String>()
        val visited = HashSet<String>()
        var pageUrl: String? = chapterUrl
        var pages = 0
        while (pageUrl != null && pages < maxPagesPerChapter) {
            if (!visited.add(pageUrl)) break
            pages++
            val page = fetch(source, pageUrl) ?: return Result.failure(IOException("取正文页失败：$pageUrl"))
            val baseEvaluator = evaluatorFor(source)
            val root = baseEvaluator.parseHtml(page, pageUrl)
            val evaluator = jsAware(baseEvaluator, root, pageUrl, source)
            val text = evaluator.strings(LegadoRule.parse(source.ruleContent.content), root)
                .joinToString("\n")
                .let(::stripHtmlIfAny)
            if (text.isNotBlank()) pieces.add(text)
            pageUrl = evaluator.firstString(LegadoRule.parse(source.ruleContent.nextContentUrl), root)
                ?.let { absolutize(it, pageUrl) }
                ?.takeIf { it.isNotBlank() }
        }
        if (pieces.isEmpty()) return Result.failure(IOException("正文为空（规则或页面结构可能已变）"))
        return Result.success(applyReplaceRegex(pieces.joinToString("\n\n"), source.ruleContent.replaceRegex))
    }

    /**
     * 整本抓取：目录 + 每章正文 → 合成 TXT。
     *
     * 任何一章失败即整体失败并说明是第几章——**不产出半本书**。
     */
    suspend fun fetchBook(
        source: LegadoBookSource,
        bookUrl: String,
        bookTitle: String,
        author: String?,
        outFile: File,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): BookFetchResult = withContext(Dispatchers.IO) {
        val chapterList = chapters(source, bookUrl).getOrElse { error ->
            return@withContext BookFetchResult.Failed(error.message ?: "取目录失败")
        }
        val builder = StringBuilder()
        builder.append(bookTitle).append('\n')
        if (!author.isNullOrBlank()) builder.append("作者：").append(author).append('\n')
        builder.append('\n')

        chapterList.forEachIndexed { index, chapter ->
            val text = content(source, chapter.url).getOrElse { error ->
                return@withContext BookFetchResult.Failed(
                    "第 ${index + 1}/${chapterList.size} 章「${chapter.name}」抓取失败：${error.message ?: "未知错误"}",
                )
            }
            builder.append(chapter.name).append('\n').append('\n')
            builder.append(text.trim()).append("\n\n\n")
            onProgress(index + 1, chapterList.size)
        }

        outFile.parentFile?.mkdirs()
        outFile.writeText(builder.toString(), Charsets.UTF_8)
        BookFetchResult.Ok(outFile, chapterList.size)
    }

    private suspend fun fetch(source: LegadoBookSource, url: String): String? =
        runCatching { fetcher.fetch(url, parseHeaders(source.header)) }.getOrNull()

    private fun evaluatorFor(source: LegadoBookSource) = LegadoRuleEvaluator()

    /** 页面解析出根节点后再挂 JS：`java.getString` 才能在当前页面上求值 */
    private fun jsAware(
        base: LegadoRuleEvaluator,
        root: RuleValue,
        pageUrl: String,
        source: LegadoBookSource,
    ): LegadoRuleEvaluator = base.withJs(
        LegadoJsRuntime(
            PageJsBridge(
                root = root,
                base = base,
                baseUrl = pageUrl,
                sourceName = source.name,
                fetch = { target -> runCatching { fetcher.fetchBlocking(target, parseHeaders(source.header)) }.getOrDefault("") },
            ),
        ),
    )

    private fun parseHeaders(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val json = org.json.JSONObject(raw)
            json.keys().asSequence().associateWith { key -> json.optString(key) }
        }.getOrDefault(emptyMap())
    }

    /** 正文规则常命中整段 HTML（`@html`）；输出要能当纯文本读，这里把标签去掉 */
    private fun stripHtmlIfAny(text: String): String =
        if (TAG_PATTERN.containsMatchIn(text)) Jsoup.parse(text).text().trim() else text.trim()

    /**
     * Legado 的 `replaceRegex` 净化。
     *
     * **写法取自真实语料**（552 条带 replaceRegex 的源）：519 条是 `##正则`（以 `##` 开头、只有一条正则）
     * = 删除匹配内容；22 条是 `##正则##替换`；其余是 JS 形态（`@js:` / `<js>`）或首尾 `##` 包起来的单正则。
     * 多条正则用 `|` 在**同一条**里并联，不是用 `##` 分隔。
     *
     * JS 形态本阶段（P4 未开工）**原样跳过**，不猜、不误删正文。
     */
    private fun applyReplaceRegex(text: String, rule: String): String {
        val trimmed = rule.trim()
        if (trimmed.isEmpty()) return text
        if (trimmed.startsWith("@js:") || trimmed.startsWith("<js>")) return text
        if (!trimmed.startsWith("##")) return text
        val parts = trimmed.removePrefix("##").split("##")
        val pattern = parts.firstOrNull()?.trim().orEmpty()
        if (pattern.isEmpty()) return text
        val replacement = if (parts.size >= 2) parts[1] else ""
        return runCatching { Regex(pattern).replace(text, replacement) }.getOrDefault(text)
    }

    private fun absolutize(url: String, base: String): String {
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        val origin = base.substringBefore("://").let { scheme ->
            val host = base.substringAfter("://").substringBefore('/')
            "$scheme://$host"
        }
        return if (url.startsWith("/")) "$origin$url" else "$origin/$url"
    }

    private companion object {
        val TAG_PATTERN = Regex("<[a-zA-Z][^>]*>")
    }
}
