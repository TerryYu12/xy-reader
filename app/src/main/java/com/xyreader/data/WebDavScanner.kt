package com.xyreader.data

import android.util.Log
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.WebDavConfigEntity
import java.net.URI
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 远程仓库扫描器：从配置的 baseUrl 起递归 PROPFIND，
 * 把远端压缩包/PDF/EPUB 文件按 "webdav://{configId}{absolutePath}" 约定入库，
 * 并逐本补页数与封面。
 * 一期不做远程图片目录书：目录只递归，不作为书入库。
 */
class WebDavScanner(
    private val dao: BookDao,
    /** 复用本地扫描器的封面/页数逻辑（远程书由 archive 层按 webdav:// 解析打开） */
    private val coverScanner: LibraryScanner,
) {

    /**
     * 扫描一个 WebDAV 配置指向的目录树，新书籍入库。
     * @return 本次新增的书籍数量
     */
    suspend fun scan(config: WebDavConfigEntity, configId: Long): Int = withContext(Dispatchers.IO) {
        // baseUrl 规范化为以 / 结尾
        val baseUrl = config.baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
        // baseUrl 的服务器路径部分（解码后，如 /dav/），用于把条目绝对路径换算成相对路径
        val baseDecodedPath = decodedBasePath(config.baseUrl)
        val client = WebDavClient()

        // 库里已有 uri，用于按 uri 去重（本次扫描新加的也会同步加进来）
        val existingUris = dao.getAllUris().toHashSet()
        // 已访问的请求 URL 集合，防服务器端环（软链/自引用导致的死循环）
        val visited = HashSet<String>()
        // 本次新插入的书（已带数据库自增 id）
        val inserted = mutableListOf<BookEntity>()

        suspend fun walk(relPath: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            val url = dirUrl(baseUrl, relPath)
            if (!visited.add(url)) return
            // 注意：日志只打 URL 与异常消息，严禁打印配置中的密码
            val entries = try {
                client.listDir(url, config.username, config.password)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "PROPFIND 失败，跳过目录: $url, ${e.message}")
                return
            }
            for (entry in entries) {
                val rel = relativeOf(baseDecodedPath, entry.href)
                // 空串是根自身（listDir 一般已跳过），防御性忽略
                if (rel.isEmpty()) continue
                when {
                    entry.isDir -> walk(rel, depth + 1)
                    // 一期只入库压缩包/PDF/EPUB 文件
                    BookFormat.isSupportedFile(entry.displayName) ->
                        insertFileBook(configId, entry, rel, existingUris, inserted)
                    // 其余条目（图片等）忽略
                }
            }
        }

        walk("", 0)

        // 扫描插入完成后逐本补元数据（页数 + 封面）
        for (book in inserted) {
            try {
                // CBR/PDF 远程打开会触发整包下载，加 60s 超时保护；失败/超时跳过不阻断，
                // totalPages 留 0，阅读时阅读器的 ensureCover 机制会补
                withTimeoutOrNull(COVER_TIMEOUT_MS) {
                    coverScanner.buildCoverAndMetadata(book)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单本封面/页数失败不阻断扫描
            }
        }
        inserted.size
    }

    /** 单个压缩包/文档文件入库（uri 按 webdav:// 约定构造，按 uri 去重） */
    private suspend fun insertFileBook(
        configId: Long,
        entry: WebDavEntry,
        relPath: String,
        existingUris: MutableSet<String>,
        inserted: MutableList<BookEntity>,
    ) {
        val uri = buildWebDavUri(configId, relPath)
        // 按 uri 去重：库里已有或本次已加过则跳过
        if (!existingUris.add(uri)) return
        val entity = BookEntity(
            title = entry.displayName.substringBeforeLast('.'),
            uri = uri,
            parentUri = buildWebDavUri(configId, relPath.substringBeforeLast('/', missingDelimiterValue = "")),
            isDirectory = false,
            format = BookFormat.fromFileName(entry.displayName).name,
            size = entry.size,
            addedAt = System.currentTimeMillis(),
        )
        val id = dao.insertBook(entity)
        if (id > 0) inserted += entity.copy(id = id)
    }

    /** 目录请求 URL：baseUrl（尾 /）+ 相对路径逐段编码；目录补尾斜杠减少 301 重定向 */
    private fun dirUrl(baseUrl: String, relPath: String): String {
        val url = baseUrl + encodePathSegments(relPath).removePrefix("/")
        return if (url.endsWith("/")) url else "$url/"
    }

    /** 服务器绝对路径（解码后）→ 相对 baseUrl 的解码路径，形如 /漫画/01.cbz；根为空串 */
    private fun relativeOf(baseDecodedPath: String, entryPath: String): String {
        val rest = if (entryPath.startsWith(baseDecodedPath)) {
            entryPath.substring(baseDecodedPath.length)
        } else {
            entryPath
        }
        val trimmed = rest.trim('/')
        return if (trimmed.isEmpty()) "" else "/$trimmed"
    }

    /** baseUrl 的解码后服务器路径，确保以 / 结尾（如 https://host/dav/ → /dav/；解析失败按 / 处理） */
    private fun decodedBasePath(baseUrl: String): String {
        val path = baseUrl.toHttpUrlOrNull()?.let { percentDecodePath(it.encodedPath) }
            ?: runCatching { URI(baseUrl).path }.getOrNull()
            ?: "/"
        return if (path.endsWith("/")) path else "$path/"
    }

    companion object {
        private const val TAG = "WebDavScanner"

        /** 根为第 0 层，最深进入第 8 层子目录，与本地扫描一致，防异常深的树拖死扫描 */
        private const val MAX_DEPTH = 8

        /** 单本封面/页数获取的超时保护（CBR/PDF 远程打开会整包下载） */
        private const val COVER_TIMEOUT_MS = 60_000L

        /**
         * 解码路径逐段 percent-encode（与 URI 约定一致，供扫描与他处复用）。
         * 用 URLEncoder 会把空格编成 '+'，须替换为 '%20'。
         */
        fun encodePathSegments(decodedPath: String): String {
            val segments = decodedPath.split('/').filter { it.isNotEmpty() }
            if (segments.isEmpty()) return ""
            return segments.joinToString("/", prefix = "/") { segment ->
                URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
            }
        }

        /** 远程书统一 uri：webdav://{configId}{逐段编码的绝对路径}；decodedPath 以 / 开头（根为空串） */
        fun buildWebDavUri(configId: Long, decodedPath: String): String =
            "webdav://$configId${encodePathSegments(decodedPath)}"
    }
}
