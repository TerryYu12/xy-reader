package com.xyreader.booksource

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 书源导入结局。
 */
sealed interface ImportResult {
    data class Success(val sources: List<LegadoBookSource>, val addedCount: Int) : ImportResult
    data class Failure(val message: String) : ImportResult
}

/**
 * 书源导入器：
 * - 纯文本 / JSON 导入
 * - 网络 URL 抓取导入（自动识别 http:// 与 https://）
 * - 本地 .json 文件流式导入（SAF Uri / InputStream）
 */
class BookSourceImporter(
    private val store: LegadoSourceStore,
    private val fetcher: SourcePageFetcher = OkHttpSourcePageFetcher(),
) {

    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        /** 判断是否为网络 URL */
        fun isUrl(input: String): Boolean {
            val trimmed = input.trim()
            return trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
        }
    }

    /**
     * 智能文本导入：若以 http:// 或 https:// 开头则走网络抓取，否则直接按 JSON 解析。
     */
    suspend fun importFromText(text: String): ImportResult = withContext(Dispatchers.IO) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return@withContext ImportResult.Failure("输入内容为空")
        }
        if (isUrl(trimmed)) {
            return@withContext importFromUrl(trimmed)
        }
        val sources = LegadoBookSource.parseImport(trimmed)
        if (sources.isEmpty()) {
            return@withContext ImportResult.Failure("没有解析到有效的书源（需要 Legado 格式的 JSON）")
        }
        val added = store.import(sources)
        ImportResult.Success(sources, added)
    }

    /**
     * 从网络 URL 抓取并导入书源 JSON
     */
    suspend fun importFromUrl(url: String): ImportResult = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (!isUrl(trimmedUrl)) {
            return@withContext ImportResult.Failure("无效的 URL 链接（需以 http:// 或 https:// 开头）")
        }
        val content = try {
            fetcher.fetch(trimmedUrl, mapOf("User-Agent" to DEFAULT_USER_AGENT))
        } catch (e: Exception) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return@withContext ImportResult.Failure("网络抓取失败：$err")
        }

        if (content.isBlank()) {
            return@withContext ImportResult.Failure("抓取成功但内容为空")
        }

        val sources = LegadoBookSource.parseImport(content)
        if (sources.isEmpty()) {
            return@withContext ImportResult.Failure("链接返回的内容中没有有效的 Legado 书源规则")
        }
        val added = store.import(sources)
        ImportResult.Success(sources, added)
    }

    /**
     * 从输入流读取并导入
     */
    suspend fun importFromStream(stream: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val text = try {
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return@withContext ImportResult.Failure("读取文件流失败：$err")
        }

        if (text.isBlank()) {
            return@withContext ImportResult.Failure("文件内容为空")
        }

        val sources = LegadoBookSource.parseImport(text)
        if (sources.isEmpty()) {
            return@withContext ImportResult.Failure("文件中没有有效的 Legado 书源规则")
        }
        val added = store.import(sources)
        ImportResult.Success(sources, added)
    }

    /**
     * 从 Android SAF Uri 读取并导入
     */
    suspend fun importFromUri(context: Context, uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            val err = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            return@withContext ImportResult.Failure("无法打开选中的文件：$err")
        } ?: return@withContext ImportResult.Failure("无法打开文件：输入流为空")

        importFromStream(stream)
    }
}
