package com.xyreader.archive

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.Chapter
import com.xyreader.core.PageSource
import com.xyreader.feedback.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/**
 * webdav:// URI 的解析结果：configId + 已 URL 编码的绝对路径（以 / 开头）。
 */
data class WebDavRef(val configId: Long, val rawPath: String)

/**
 * 解析远程书 URI："webdav://{configId}{absolutePath}"。
 * 例 "webdav://2/%E6%BC%AB%E7%94%BB/01.cbz" → configId=2，rawPath="/%E6%BC%AB%E7%94%BB/01.cbz"。
 * rawPath 保持编码形态，直接拼到 baseUrl 后使用（不再二次编码）。
 */
fun parseWebDavUri(uri: String): WebDavRef {
    val parsed = runCatching { URI(uri) }.getOrNull()
        ?: throw IllegalArgumentException("无法解析 WebDAV URI: $uri")
    val configId = parsed.host?.toLongOrNull()
        ?: throw IllegalArgumentException("WebDAV URI 缺少有效的配置 id: $uri")
    val rawPath = parsed.rawPath
    if (rawPath.isNullOrEmpty() || !rawPath.startsWith("/")) {
        throw IllegalArgumentException("WebDAV URI 缺少以 / 开头的文件路径: $uri")
    }
    return WebDavRef(configId, rawPath)
}

/**
 * 拼最终请求 URL：baseUrl（末尾无 / 则补 /）+ 已编码 rawPath。
 * rawPath 每段已 URL 编码，这里不做任何再编码。
 */
fun buildWebDavUrl(baseUrl: String, rawPath: String): String {
    val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    return base + rawPath.removePrefix("/")
}

/**
 * WebDAV 远程书页面源工厂：
 * - zip/7z/tar：[HttpRangeChannel] 直接喂 commons-compress 的 ZipFile / SevenZFile / TarFile，
 *   真流式（HTTP Range 随机读，不下载整包）；
 * - rar/pdf：整包下载到 cacheDir/remote_cache 后复用本地 RarPageSource / PdfPageSource。
 */
object RemoteArchiveSources {

    /** 远程缓存目录大小上限：超过即触发清理 */
    private const val CACHE_TRIM_THRESHOLD = 1L shl 30

    /** 清理目标：按最后修改时间删最旧文件直到该值以下 */
    private const val CACHE_TARGET = 800L * 1024L * 1024L

    /** 打开远程 CBZ/EPUB：HttpRangeChannel 直接喂 commons-compress ZipFile（中央目录随机访问） */
    fun openRemoteZip(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): PageSource {
        val channel = HttpRangeChannel(url, username, password)
        var zip: ZipFile? = null
        try {
            zip = ZipFile(channel)
            val entries = PageSources.collectImageEntries(
                zip.entries.iterator().asSequence(),
                isDirectory = { it.isDirectory },
                nameOf = { it.name },
            )
            if (entries.isEmpty()) {
                throw IllegalStateException("压缩包内没有图片页面: ${book.uri}")
            }
            return RemoteZipPageSource(zip, channel, entries)
        } catch (t: Throwable) {
            // 打开中途失败：ZipFile.close 会顺带关闭 channel，channel.close 幂等兜底
            runCatching { zip?.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程 ZipFile 异常", it) }
            runCatching { channel.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CB7/7Z：SevenZFile 直接跑在 HttpRangeChannel 上 */
    fun openRemoteSevenZip(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): PageSource {
        val channel = HttpRangeChannel(url, username, password)
        var sevenZip: SevenZFile? = null
        try {
            sevenZip = SevenZFile(channel)
            val entries = PageSources.collectImageEntries(
                sevenZip.entries.asSequence(),
                isDirectory = { it.isDirectory },
                nameOf = { it.name },
            )
            if (entries.isEmpty()) {
                throw IllegalStateException("7z 包内没有图片页面: ${book.uri}")
            }
            return RemoteSevenZipPageSource(sevenZip, channel, entries)
        } catch (t: Throwable) {
            runCatching { sevenZip?.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程 7z 归档异常", it) }
            runCatching { channel.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CBT/TAR：TarFile 直接跑在 HttpRangeChannel 上（SeekableByteChannel 重载） */
    fun openRemoteTar(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): PageSource {
        val channel = HttpRangeChannel(url, username, password)
        var tarFile: TarFile? = null
        try {
            tarFile = TarFile(channel)
            val entries = PageSources.collectImageEntries(
                tarFile.entries.asSequence(),
                isDirectory = { it.isDirectory },
                nameOf = { it.name },
            )
            if (entries.isEmpty()) {
                throw IllegalStateException("TAR 包内没有图片页面: ${book.uri}")
            }
            return RemoteTarPageSource(tarFile, channel, entries)
        } catch (t: Throwable) {
            runCatching { tarFile?.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程 TAR 归档异常", it) }
            runCatching { channel.close() }
                .onFailure { AppLog.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CBR：整包下载到缓存后复用本地 RarPageSource（junrar 只接受本地文件） */
    fun openRemoteRar(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): PageSource {
        val file = ensureRemoteFile(context, book, url, username, password)
        // 伪 BookEntity：copy 保留 title 等元数据，仅把 uri 指到本地缓存文件（file:// 本地实现已支持）
        val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
        return RarPageSource.open(context, localBook)
    }

    /** 打开远程 PDF：整包下载到缓存后复用本地 PdfPageSource（PdfRenderer 需要 fd） */
    fun openRemotePdf(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): PageSource {
        val file = ensureRemoteFile(context, book, url, username, password)
        val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
        return PdfPageSource.open(context, localBook)
    }

    /**
     * 确保远程整包已下载到本地缓存：cacheDir/remote_cache/{uri 的 SHA-1}.{ext}。
     * 已存在（且 >0 字节）直接复用；否则普通 GET 流式下载到 .part 再 rename（不写半截正式文件）。
     * 下载前清理：remote_cache 总量 >1GB 时按最后修改时间从最旧开始删，直到 <800MB。
     */
    private fun ensureRemoteFile(
        context: Context,
        book: BookEntity,
        url: String,
        username: String?,
        password: String?,
    ): File {
        val dir = File(context.cacheDir, "remote_cache").apply { mkdirs() }
        val target = File(dir, "${sha1Hex(book.uri)}.${cacheExtension(book, url)}")
        if (target.exists() && target.length() > 0) return target

        trimRemoteCache(dir, keep = target)

        val tmp = File(dir, "${target.name}.part")
        try {
            val builder = Request.Builder().url(url)
            if (!username.isNullOrEmpty()) {
                // 密码只进请求头，绝不落日志
                builder.header("Authorization", Credentials.basic(username, password ?: ""))
            }
            val response = try {
                HttpRangeChannel.defaultHttpClient().newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw IOException("远程文件下载失败（网络错误）: ${e.message}", e)
            }
            response.use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw IOException("HTTP ${resp.code}: 远程文件下载失败（认证失败，请检查 WebDAV 用户名/应用密码）")
                }
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: 远程文件下载失败")
                }
                val body = resp.body ?: throw IOException("HTTP ${resp.code}: 响应无内容")
                tmp.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            if (tmp.length() <= 0L) {
                throw IOException("远程文件下载失败：响应成功但内容为 0 字节")
            }
            if (!tmp.renameTo(target)) {
                // rename 失败（极端并发下目标可能已被创建）：目标有效则用目标，否则重试 rename
                if (target.exists() && target.length() > 0) {
                    tmp.delete()
                } else {
                    check(tmp.renameTo(target)) { "缓存文件重命名失败: ${tmp.name}" }
                }
            }
            return target
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    /** remote_cache 总量超过 1GB 时按最后修改时间删最旧文件，直到 <800MB（本次正要生成的文件不动） */
    private fun trimRemoteCache(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_TRIM_THRESHOLD) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= CACHE_TARGET) break
            if (f == keep) continue
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /** 远程缓存扩展名：优先从请求 URL 末段提取（已编码路径的扩展名为 ASCII），取不到按书籍格式兜底 */
    private fun cacheExtension(book: BookEntity, url: String): String {
        val fromUrl = url.substringBefore('?')
            .substringAfterLast('/')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        if (fromUrl.isNotEmpty() && fromUrl.length <= 8 &&
            fromUrl.all { it in 'a'..'z' || it in '0'..'9' }
        ) {
            return fromUrl
        }
        return when (runCatching { BookFormat.valueOf(book.format) }.getOrNull()) {
            BookFormat.CBR -> "rar"
            BookFormat.PDF -> "pdf"
            else -> "bin"
        }
    }

    /** URI 的 SHA-1 十六进制：缓存文件名用，规避特殊字符与长度问题 */
    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * 远程 CBZ/EPUB 页面源：commons-compress [ZipFile] 直接跑在 [HttpRangeChannel] 上。
 */
class RemoteZipPageSource internal constructor(
    private val zip: ZipFile,
    private val channel: HttpRangeChannel,
    imageEntries: List<ZipArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（条目列表已按页序排好） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // channel 内部有 position 状态且被 ZipFile 共享：Mutex 串行化保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { zip.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // ZipFile.close() 会关闭传入的 channel；channel.close() 幂等兜底
        runCatching { zip.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程 ZipFile 异常", it) }
        runCatching { channel.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程通道异常", it) }
    }
}

/**
 * 远程 CB7/7Z 页面源：commons-compress [SevenZFile] 直接跑在 [HttpRangeChannel] 上。
 */
class RemoteSevenZipPageSource internal constructor(
    private val sevenZip: SevenZFile,
    private val channel: HttpRangeChannel,
    imageEntries: List<SevenZArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（条目列表已按页序排好） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // channel 内部有 position 状态且被 SevenZFile 共享：Mutex 串行化保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { sevenZip.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // SevenZFile.close() 会关闭传入的 channel；channel.close() 幂等兜底
        runCatching { sevenZip.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程 7z 归档异常", it) }
        runCatching { channel.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程通道异常", it) }
    }
}

/**
 * 远程 CBT/TAR 页面源：commons-compress [TarFile] 直接跑在 [HttpRangeChannel] 上。
 */
class RemoteTarPageSource internal constructor(
    private val tarFile: TarFile,
    private val channel: HttpRangeChannel,
    imageEntries: List<TarArchiveEntry>,
) : AbstractPageSource() {

    private val entries = imageEntries.toList()

    /** 章节：构造时按页表一次性算好（条目列表已按页序排好） */
    override val chapters: List<Chapter> =
        PageSources.buildChapters(entries, nameOf = { it.name })

    private val ioLock = Mutex()

    override val cachedPageCount: Int get() = entries.size

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        // channel 内部有 position 状态且被 TarFile 共享：Mutex 串行化保证线程安全
        return ioLock.withLock {
            withContext(Dispatchers.IO) {
                PageSources.decodeEntryStream(index) { tarFile.getInputStream(entries[index]) }
            }
        }
    }

    override fun close() = onFirstClose {
        // TarFile.close() 会关闭传入的 channel；channel.close() 幂等兜底
        runCatching { tarFile.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程 TAR 归档异常", it) }
        runCatching { channel.close() }
            .onFailure { AppLog.w(PAGE_SOURCE_TAG, "关闭远程通道异常", it) }
    }
}
