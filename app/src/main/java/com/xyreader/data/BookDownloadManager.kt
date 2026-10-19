package com.xyreader.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.xyreader.core.Acquisition
import com.xyreader.core.BookFormat
import com.xyreader.core.DownloadEntry
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** 下载任务状态：下载中 / 已下载待入库 / 失败 / 已入库（离线可读） */
internal enum class BookDownloadStatus { DOWNLOADING, READY, FAILED, IMPORTED }

internal data class BookDownloadSnapshot(
    val entry: DownloadEntry,
    val status: BookDownloadStatus,
    /** 0..100；-1 = 系统未报告总长度 */
    val progress: Int,
    /** 入库后的书籍 id（IMPORTED 时非空） */
    val bookId: Long? = null,
    val error: String? = null,
)

/** 校验通过的下载文件交给书库（复制进私有导入目录 + 写库）；测试可注入假实现 */
internal fun interface DownloadedBookImporter {
    suspend fun import(file: File, storedName: String, title: String): Long
}

/**
 * 书籍下载基础设施。
 *
 * 分工：系统 DownloadManager 负责传输（进程被杀后仍在跑、自带通知与进度），
 * 本类负责下载身份与唯一命名、任务持久化、进度查询、内容校验，以及「先落盘后入库」。
 *
 * 与来源无关：调用方给出 [DownloadEntry]（来源 ID + 条目 ID + 格式）与来源解析出的
 * [Acquisition.DirectFile]。来源适配层（[com.xyreader.core.BookSource]）本阶段无实现。
 *
 * 已知取舍：
 * - 断点续传依赖系统 DownloadManager（应用进程结束不中断；失败任务重试是重新提交，不保证字节续传）。
 * - 主动校验只做「大小 + HTML/格式一致性」，不做哈希/签名——书籍来源没有可比的官方哈希。
 * - 任务记录存 SharedPreferences（与更新器同一形态），不新增 Room 表，避免数据库迁移。
 */
internal class BookDownloadManager(
    context: Context,
    private val importer: DownloadedBookImporter = RepositoryBookImporter(context.applicationContext),
) {
    private val appContext = context.applicationContext
    private val downloads = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 提交一次下载。
     *
     * 同一身份（[DownloadEntry.identityKey]）已有下载中/已下载/已入库的任务时不重复提交；
     * 失败状态则视为重试，重新提交（旧系统任务与文件保留，不做隐式清理）。
     */
    suspend fun enqueue(entry: DownloadEntry, acquisition: Acquisition.DirectFile) = withContext(Dispatchers.IO) {
        taskMutex.withLock {
            val previous = try {
                readRecord()
            } catch (_: JSONException) {
                // 记录损坏时，用户显式重试可覆盖；不删除旧系统任务与文件
                null
            } catch (_: ClassCastException) {
                null
            }
            if (previous != null && previous.entry.identityKey() == entry.identityKey()) {
                val state = snapshotLocked(previous)
                if (state.status != BookDownloadStatus.FAILED) return@withLock
            }
            val uri = Uri.parse(acquisition.url)
            if (!isAllowedDownloadUri(uri)) {
                throw IOException("下载地址必须使用 HTTPS（局域网/本机地址可用 HTTP）")
            }
            val extension = extensionFor(entry, uri)
                ?: throw IOException("无法确定下载格式：来源需给出格式或可识别扩展名的地址")
            val storedName = BookDownloadNaming.storedFileName(entry, extension)
            val directory = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: throw IOException("下载目录不可用")
            val relativePath = "${STAGING_DIR}/${storedName}"
            val staged = File(directory, relativePath)
            staged.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建下载目录")
            }
            val request = DownloadManager.Request(uri)
                .setTitle(entry.title)
                .setDescription("正在下载书籍")
                .setMimeType(acquisition.mimeType ?: MIME_BY_EXTENSION[extension])
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, relativePath)
            val id = downloads.enqueue(request)
            writeRecord(Record(id, entry, staged.absolutePath, storedName, acquisition.sizeBytes, bookId = null))
        }
    }

    /** 当前任务状态；无记录返回 null。已入库的任务直接返回 IMPORTED，不再查询系统。 */
    suspend fun snapshot(): BookDownloadSnapshot? = withContext(Dispatchers.IO) {
        taskMutex.withLock { readRecord()?.let { snapshotLocked(it) } }
    }

    /** 只清应用侧记录，系统任务与已落盘文件保留（与更新器同一语义） */
    suspend fun forget(): Unit = withContext(Dispatchers.IO) {
        taskMutex.withLock {
            if (!prefs.edit().remove(KEY_RECORD).commit()) throw IOException("无法保存下载状态")
        }
    }

    private suspend fun snapshotLocked(record: Record): BookDownloadSnapshot {
        record.bookId?.let { return BookDownloadSnapshot(record.entry, BookDownloadStatus.IMPORTED, 100, it) }
        return try {
            downloads.query(DownloadManager.Query().setFilterById(record.id)).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) {
                    return failed(record, "系统下载任务已不存在，请重新下载")
                }
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                when (status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                        val progress = if (total > 0L) {
                            (downloaded.toDouble() / total * 100).toInt().coerceIn(0, 100)
                        } else -1
                        BookDownloadSnapshot(record.entry, BookDownloadStatus.DOWNLOADING, progress)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> finalizeDownload(record, total)
                    DownloadManager.STATUS_FAILED -> {
                        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                        failed(record, downloadFailureMessage(reason))
                    }
                    else -> failed(record, "无法识别系统下载状态：$status")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(record, e.message ?: "无法读取下载状态")
        }
    }

    /**
     * 传输完成后的收尾：大小校验 → 内容校验 → 交书库入库 → 清掉外部暂存副本。
     *
     * 校验不通过一律停在 FAILED，文件不入库（半成品/登录页不会变成书架上的书）。
     * 入库成功才 remove 系统任务：书库私有副本是唯一事实来源，外部暂存副本不再需要。
     */
    private suspend fun finalizeDownload(record: Record, reportedTotal: Long): BookDownloadSnapshot {
        val staged = File(record.sourcePath)
        if (!staged.isFile) return failed(record, "下载的书籍文件已丢失，请重新下载")
        validateSize(record, reportedTotal, staged.length())
        validateContent(record, staged)
        val bookId = importer.import(staged, record.storedName, record.entry.title)
        if (bookId <= 0) throw IOException("书籍入库失败")
        runCatching { downloads.remove(record.id) }
        writeRecord(record.copy(bookId = bookId))
        return BookDownloadSnapshot(record.entry, BookDownloadStatus.IMPORTED, 100, bookId)
    }

    /** 大小三对照：来源声明值（若有）、系统报告总长（若有）、实际文件长度 */
    private fun validateSize(record: Record, reportedTotal: Long, actual: Long) {
        val declared = record.declaredSize
        if (declared != null && declared > 0 && declared != actual) {
            throw IOException("下载文件大小不符（来源声明 $declared，实际 $actual），已丢弃")
        }
        if (reportedTotal > 0 && reportedTotal != actual) {
            throw IOException("下载文件不完整（系统报告 $reportedTotal，实际 $actual），已丢弃")
        }
    }

    private fun validateContent(record: Record, staged: File) {
        if (BookContentSniffer.looksLikeHtml(staged)) {
            throw IOException("下载内容像是网页/登录页，不是书籍文件，已丢弃")
        }
        val declared = record.entry.format
            ?.let { runCatching { BookFormat.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?: BookFormat.UNKNOWN
        val sniffed = BookContentSniffer.sniffExtension(staged)
        if (!BookContentSniffer.declaredMatchesSniffed(declared, sniffed)) {
            throw IOException(
                "下载内容与声明格式不符（声明 ${declared.displayName}，实际 ${sniffed ?: "未知"}），已丢弃",
            )
        }
    }

    private fun failed(record: Record, message: String) =
        BookDownloadSnapshot(record.entry, BookDownloadStatus.FAILED, -1, error = message)

    /** 落盘扩展名：优先来源声明的格式，其次地址里的扩展名（都必须属于可入库格式） */
    private fun extensionFor(entry: DownloadEntry, uri: Uri): String? {
        val declared = entry.format
            ?.let { runCatching { BookFormat.valueOf(it.trim().uppercase()) }.getOrNull() }
            ?.let { EXTENSION_BY_FORMAT[it] }
        if (declared != null) return declared
        val fromUrl = uri.lastPathSegment
            ?.substringAfterLast('.', "")
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return fromUrl.takeIf { BookFormat.isSupportedFile("book.$it") }
    }

    private fun readRecord(): Record? {
        val raw = prefs.getString(KEY_RECORD, null) ?: return null
        val json = JSONObject(raw)
        val entry = json.getJSONObject("entry")
        return Record(
            id = json.getLong("id"),
            entry = DownloadEntry(
                sourceId = entry.getString("sourceId"),
                itemId = entry.getString("itemId"),
                title = entry.getString("title"),
                format = entry.optString("format").takeIf { it.isNotBlank() },
                version = entry.optString("version").takeIf { it.isNotBlank() },
            ),
            sourcePath = json.getString("sourcePath"),
            storedName = json.getString("storedName"),
            declaredSize = if (json.has("declaredSize")) json.getLong("declaredSize") else null,
            bookId = if (json.has("bookId")) json.getLong("bookId") else null,
        )
    }

    private fun writeRecord(record: Record) {
        val entry = record.entry
        val json = JSONObject()
            .put("id", record.id)
            .put("sourcePath", record.sourcePath)
            .put("storedName", record.storedName)
            .put("entry", JSONObject()
                .put("sourceId", entry.sourceId)
                .put("itemId", entry.itemId)
                .put("title", entry.title)
                .put("format", entry.format ?: "")
                .put("version", entry.version ?: ""))
        record.declaredSize?.let { json.put("declaredSize", it) }
        record.bookId?.let { json.put("bookId", it) }
        if (!prefs.edit().putString(KEY_RECORD, json.toString()).commit()) {
            throw IOException("无法保存下载状态")
        }
    }

    private data class Record(
        val id: Long,
        val entry: DownloadEntry,
        val sourcePath: String,
        val storedName: String,
        val declaredSize: Long?,
        val bookId: Long?,
    )

    private companion object {
        const val PREFS_NAME = "book_download"
        const val KEY_RECORD = "task"
        const val STAGING_DIR = "book-downloads"

        /** 不同管理器实例（Activity 重建等）共享锁，避免重复点击提交多个任务 */
        val taskMutex = Mutex()

        val EXTENSION_BY_FORMAT = mapOf(
            BookFormat.CBZ to "cbz", BookFormat.CBR to "cbr", BookFormat.CB7 to "cb7",
            BookFormat.CBT to "cbt", BookFormat.PDF to "pdf", BookFormat.EPUB to "epub",
            BookFormat.MOBI to "mobi", BookFormat.AZW3 to "azw3", BookFormat.TXT to "txt",
            BookFormat.MD to "md",
        )

        val MIME_BY_EXTENSION = mapOf(
            "epub" to "application/epub+zip",
            "pdf" to "application/pdf",
            "cbz" to "application/vnd.comicbook+zip",
            "cbr" to "application/vnd.comicbook-rar",
            "cb7" to "application/x-7z-compressed",
            "cbt" to "application/x-tar",
            "mobi" to "application/x-mobipocket-ebook",
            "azw3" to "application/vnd.amazon.ebook",
            "txt" to "text/plain",
            "md" to "text/markdown",
        )
    }
}

/**
 * 下载地址准入：HTTPS 一律允许；明文 HTTP 只允许回环与 RFC1918/链路本地地址
 * （自建目录常以局域网地址暴露），其余一律拒绝，避免把书籍内容明文上公网。
 */
internal fun isAllowedDownloadUri(uri: Uri): Boolean {
    val scheme = uri.scheme?.lowercase() ?: return false
    val host = uri.host?.lowercase()?.trim().orEmpty()
    if (host.isEmpty()) return false
    if (scheme == "https") return true
    if (scheme != "http") return false
    if (host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]") return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { it.toIntOrNull()?.takeIf { v -> v in 0..255 } ?: return false }
    val (a, b) = octets
    return a == 10 || a == 127 ||
        (a == 192 && b == 168) ||
        (a == 172 && b in 16..31) ||
        (a == 169 && b == 254)
}

/** 下载失败原因 → 用户可读说明 */
private fun downloadFailureMessage(reason: Int): String = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，无法下载"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存放位置不可用"
    DownloadManager.ERROR_FILE_ERROR -> "无法写入下载文件"
    DownloadManager.ERROR_HTTP_DATA_ERROR -> "网络传输失败，请重新下载"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "重定向次数过多"
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "来源返回了无法处理的响应"
    in 400..599 -> "下载失败：HTTP $reason"
    else -> "下载失败（错误码 $reason），请重试"
}

/**
 * 落盘命名：来源 ID + 条目 ID + 版本 + 身份哈希 → 文件名。
 *
 * 三条硬要求：
 * 1. 跨来源唯一——同名书来自不同来源不能互相覆盖（标题不参与命名）；
 * 2. 可重复——同一身份再下必得同名，重复导入幂等（书库按 uri 复用记录）；
 * 3. 可读可查——保留来源与条目片段便于排障，非法字符替换、超长截断，中文保留。
 */
internal object BookDownloadNaming {
    private const val MAX_BASE_LENGTH = 96

    fun storedFileName(entry: DownloadEntry, extension: String): String {
        val base = buildString {
            append(slug(entry.sourceId, 24))
            append('_')
            append(slug(entry.itemId, 32))
            entry.version?.takeIf { it.isNotBlank() }?.let {
                append('_')
                append(slug(it, 16))
            }
            append('_')
            append(shortHash(entry.identityKey()))
        }
        return "${base.take(MAX_BASE_LENGTH)}.$extension"
    }

    /** 路径分隔符、控制字符等非法字符 → `_`；空或超长时兜底，绝不产生空文件名 */
    internal fun slug(value: String, max: Int): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\s]+"), "_")
            .trim('_')
        if (cleaned.isEmpty()) return "x"
        return cleaned.take(max)
    }

    /** 身份哈希前 8 位：清洗与截断可能让不同身份撞成同名，哈希兜住唯一性 */
    internal fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(4).joinToString("") { "%02x".format(it) }
    }
}

/** 默认入库实现：走全局仓库单例（与来源无关的下载层只依赖 DownloadedBookImporter 抽象） */
private class RepositoryBookImporter(private val appContext: Context) : DownloadedBookImporter {
    override suspend fun import(file: File, storedName: String, title: String): Long =
        AppGraph.libraryRepository(appContext).importDownloadedFile(file, storedName, title)
}
