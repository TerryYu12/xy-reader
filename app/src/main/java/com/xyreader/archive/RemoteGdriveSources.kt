package com.xyreader.archive

import android.content.Context
import android.util.Log
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.PageSource
import com.xyreader.data.AppGraph
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarFile
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * gdrive:// URI 的解析结果：账号 id + Drive 文件 id。
 */
data class GdriveRef(val accountId: Long, val fileId: String)

/**
 * 解析远程书 URI："gdrive://{accountId}/{fileId}"。
 * 例 "gdrive://3/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"
 * → accountId=3，fileId="1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms"。
 *
 * fileId 字符集为 [A-Za-z0-9_-]，不含需编码字符，正常无需解码；
 * 稳妥起见仅当其中含 % 时做一次 URL 解码（避免 + 被误转空格），失败则回退原始值。
 */
fun parseGdriveUri(uri: String): GdriveRef {
    val parsed = runCatching { URI(uri) }.getOrNull()
        ?: throw IllegalArgumentException("无法解析 Google Drive URI: $uri")
    val accountId = parsed.host?.toLongOrNull()
        ?: throw IllegalArgumentException("Google Drive URI 缺少有效的账号 id: $uri")
    val rawFileId = parsed.path?.removePrefix("/").orEmpty()
    if (rawFileId.isEmpty()) {
        throw IllegalArgumentException("Google Drive URI 缺少文件 id: $uri")
    }
    val fileId = if (rawFileId.contains('%')) {
        // 出现 % 视为被编码过：解码一次，非法序列（IllegalArgumentException）时回退原值
        runCatching { URLDecoder.decode(rawFileId, "UTF-8") }.getOrDefault(rawFileId)
    } else {
        rawFileId
    }
    if (fileId.isEmpty() || fileId.contains('/')) {
        throw IllegalArgumentException("Google Drive URI 的文件 id 无效: $uri")
    }
    return GdriveRef(accountId, fileId)
}

/**
 * Google Drive 远程书页面源工厂：
 * - zip/7z/tar：[HttpRangeChannel]（动态 Bearer token 鉴权）直接喂 commons-compress 的
 *   ZipFile / SevenZFile / TarFile，真流式（Drive v3 alt=media 下载端点原生支持 Range，
 *   206 + Content-Range，[HttpRangeChannel.size] 的 0-0 探测直接可用）；
 * - rar/pdf：整包下载到 cacheDir/remote_cache 后复用本地 RarPageSource / PdfPageSource。
 *
 * 与 WebDAV 工厂（[RemoteArchiveSources]）的差异只在鉴权：Google Drive 用会过期的
 * OAuth access token，由动态 authHeaderProvider 在每次请求时取有效 token（Basic 静态头
 * 无法续期）。整包下载与缓存目录（cacheDir/remote_cache）与 WebDAV 共用，统一受容量清理。
 */
object RemoteGdriveSources {

    /** remote_cache 目录大小上限：超过即触发清理（与 WebDAV 下载共用同一目录与阈值） */
    private const val CACHE_TRIM_THRESHOLD = 1L shl 30

    /** 清理目标：按最后修改时间删最旧文件直到该值以下 */
    private const val CACHE_TARGET = 800L * 1024L * 1024L

    /**
     * Drive v3 下载端点：alt=media 直出文件内容，supportsAllDrives=true 兼容共享云端硬盘。
     * fileId 字符集为 [A-Za-z0-9_-]，直接拼接无路径注入风险。
     * 该端点会 302 到 googleusercontent 的一次性凭证 URL：OkHttp 默认跟随重定向，
     * 跨 host 时会自动去掉 Authorization 头（凭证已在 Location URL 内），无需特殊处理。
     */
    private fun downloadUrl(fileId: String): String =
        "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true"

    /**
     * 构造动态 Bearer 鉴权头提供者：每次 HTTP 请求时取当前有效的 access token。
     *
     * 线程与阻塞说明：provider 只会被 [HttpRangeChannel] 的网络请求路径（size 探测、
     * Range 块读）invoke，调用点全部在网络线程（PageSource 渲染时的 Dispatchers.IO，
     * 或 ArchiveFactory.open 的调用方 IO 线程；OkHttp 工作线程绝非主线程），
     * runBlocking 桥接挂起的 refreshGdriveAccessToken 是安全的。
     * refreshGdriveAccessToken 内部有缓存与 Mutex：token 未过期时几乎零开销，
     * 过期时并发下也只真正刷新一次。
     * clientSecret / refreshToken / token 只进请求头，绝不落任何日志。
     */
    private fun bearerAuthHeaderProvider(context: Context, accountId: Long): () -> String? = {
        val token = runBlocking {
            AppGraph.libraryRepository(context).refreshGdriveAccessToken(accountId)
        }
        "Bearer $token"
    }

    /** 打开远程 CBZ/EPUB：HttpRangeChannel（Bearer 动态鉴权）直接喂 commons-compress ZipFile */
    fun openGdriveZip(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): PageSource {
        val channel = HttpRangeChannel(
            downloadUrl(fileId), null, null,
            authHeaderProvider = bearerAuthHeaderProvider(context, accountId),
        )
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
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程 ZipFile 异常", it) }
            runCatching { channel.close() }
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CB7/7Z：SevenZFile 直接跑在 HttpRangeChannel 上 */
    fun openGdriveSevenZip(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): PageSource {
        val channel = HttpRangeChannel(
            downloadUrl(fileId), null, null,
            authHeaderProvider = bearerAuthHeaderProvider(context, accountId),
        )
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
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程 7z 归档异常", it) }
            runCatching { channel.close() }
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CBT/TAR：TarFile 直接跑在 HttpRangeChannel 上（SeekableByteChannel 重载） */
    fun openGdriveTar(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): PageSource {
        val channel = HttpRangeChannel(
            downloadUrl(fileId), null, null,
            authHeaderProvider = bearerAuthHeaderProvider(context, accountId),
        )
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
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程 TAR 归档异常", it) }
            runCatching { channel.close() }
                .onFailure { Log.w(PAGE_SOURCE_TAG, "回收远程通道异常", it) }
            throw t
        }
    }

    /** 打开远程 CBR：整包下载到缓存后复用本地 RarPageSource（junrar 只接受本地文件） */
    fun openGdriveRar(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): PageSource {
        val file = ensureGdriveFile(context, book, fileId, accountId)
        // 伪 BookEntity：copy 保留 title 等元数据，仅把 uri 指到本地缓存文件（file:// 本地实现已支持）
        val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
        return RarPageSource.open(context, localBook)
    }

    /** 打开远程 PDF：整包下载到缓存后复用本地 PdfPageSource（PdfRenderer 需要 fd） */
    fun openGdrivePdf(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): PageSource {
        val file = ensureGdriveFile(context, book, fileId, accountId)
        val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
        return PdfPageSource.open(context, localBook)
    }

    /**
     * 确保远程整包已下载到本地缓存：cacheDir/remote_cache/{uri 的 SHA-1}.{ext}。
     * 目录与 WebDAV 整包下载共用（trimRemoteCache 触发时统一清理）；
     * book.uri 含 gdrive:// 前缀，SHA-1 与任何 WebDAV 书的 URI 必然不同，文件名不冲突。
     * 已存在（且 >0 字节）直接复用；否则带动态 Bearer 头 GET 流式下载到 .part 再 rename
     * （不写半截正式文件）。调用方须在 IO 线程（由 ArchiveFactory.open 的契约保证）。
     */
    private fun ensureGdriveFile(
        context: Context,
        book: BookEntity,
        fileId: String,
        accountId: Long,
    ): File {
        val dir = File(context.cacheDir, "remote_cache").apply { mkdirs() }
        val target = File(dir, "${sha1Hex(book.uri)}.${cacheExtension(book)}")
        if (target.exists() && target.length() > 0) return target

        trimRemoteCache(dir, keep = target)

        val tmp = File(dir, "${target.name}.part")
        try {
            val builder = Request.Builder().url(downloadUrl(fileId))
            // 动态 Bearer token：下载发起时同步取（内部缓存，未过期几乎零开销）。
            // 调用点在 IO 线程，runBlocking 安全；token 只进请求头，绝不落日志。
            builder.header(
                "Authorization",
                "Bearer " + runBlocking {
                    AppGraph.libraryRepository(context).refreshGdriveAccessToken(accountId)
                },
            )
            val response = try {
                HttpRangeChannel.defaultHttpClient().newCall(builder.build()).execute()
            } catch (e: IOException) {
                throw IOException("Google Drive 文件下载失败（网络错误）: ${e.message}", e)
            }
            response.use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    throw IOException("HTTP ${resp.code}: 下载失败（授权已失效，请重新授权 Google Drive）")
                }
                if (!resp.isSuccessful) {
                    throw IOException("HTTP ${resp.code}: Google Drive 文件下载失败")
                }
                val body = resp.body ?: throw IOException("HTTP ${resp.code}: 响应无内容")
                tmp.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            if (tmp.length() <= 0L) {
                throw IOException("Google Drive 文件下载失败：响应成功但内容为 0 字节")
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

    /** 远程缓存扩展名：gdrive 下载 URL 不含文件名，从书籍格式映射 */
    private fun cacheExtension(book: BookEntity): String =
        when (runCatching { BookFormat.valueOf(book.format) }.getOrNull()) {
            BookFormat.CBR -> "rar"
            BookFormat.PDF -> "pdf"
            else -> "bin"
        }

    /** URI 的 SHA-1 十六进制：缓存文件名用，规避特殊字符与长度问题 */
    private fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
