package com.xyreader.core

import android.content.Context
import com.xyreader.archive.DirectoryPageSource
import com.xyreader.archive.EpubImageBasedException
import com.xyreader.archive.HttpRangeChannel
import com.xyreader.archive.MobiNotTextException
import com.xyreader.archive.MobiPageSource
import com.xyreader.archive.NovelPageSource
import com.xyreader.archive.NovelStyle
import com.xyreader.archive.NovelTextExtractor
import com.xyreader.archive.PageSources
import com.xyreader.archive.PdfFolderPageSource
import com.xyreader.archive.PdfPageSource
import com.xyreader.archive.RarPageSource
import com.xyreader.archive.RemoteArchiveSources
import com.xyreader.archive.RemoteGdriveSources
import com.xyreader.archive.SevenZipPageSource
import com.xyreader.archive.TarPageSource
import com.xyreader.archive.ZipPageSource
import com.xyreader.archive.buildWebDavUrl
import com.xyreader.archive.parseGdriveUri
import com.xyreader.archive.parseWebDavUri
import com.xyreader.data.AppGraph
import com.xyreader.data.GoogleDriveClient
import java.io.FileInputStream
import java.nio.channels.SeekableByteChannel
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * 按格式路由到具体 PageSource 实现（见 com.xyreader.archive 包）。
 *
 * 路由表：
 * - webdav://        -> openRemote（远程书：CBZ/EPUB/CB7/CBT/MOBI/AZW3 走 HTTP Range 流式随机读，
 *                      CBR/PDF/TXT 下载整包到缓存后复用本地实现）
 * - gdrive://        -> openGdrive（远程书：CBZ/EPUB/CB7/CBT/MOBI/AZW3 走 HTTP Range 流式随机读
 *                      （OAuth Bearer 动态鉴权），CBR/PDF/TXT 下载整包到缓存后复用本地实现）
 * - TXT              -> NovelPageSource（文字小说：编码探测 → 章节切分 → 预分页 → 透明底文字页）
 * - CBZ / ZIP        -> ZipPageSource（commons-compress ZipFile）
 * - EPUB             -> 文本型（NovelTextExtractor.parseEpub 成功）-> NovelPageSource 文字管线；
 *                       图片型（首个 xhtml 含 <img> 或 spine 无文本条目）-> 回退 ZipPageSource
 * - MOBI / AZW3      -> 文本版（无压缩/PalmDOC 压缩 + 无加密 + 无图片资源）-> NovelPageSource；
 *                       其余（HUFF/CDIC、DRM、图片版）-> 回退 MobiPageSource（PDB 图片管线）
 * - CBR / RAR        -> RarPageSource（junrar，content URI 先复制到缓存目录）
 * - CB7 / 7Z         -> SevenZipPageSource（commons-compress SevenZipFile）
 * - CBT / TAR        -> TarPageSource（commons-compress TarFile）
 * - PDF              -> PdfPageSource（android.graphics.pdf.PdfRenderer）
 * - DIRECTORY        -> DirectoryPageSource（DocumentFile 目录内图片）
 * - PDF_FOLDER       -> PdfFolderPageSource（同文件夹多个 PDF 合并的合集，每个 PDF 一章，子 PDF 按需打开；
 *                       仅本地仓库，webdav:// / gdrive:// 暂不支持 -> UnsupportedOperationException）
 * - UNKNOWN          -> IllegalArgumentException
 *
 * 样式：[open] 的 [NovelStyle] 重载仅在格式命中文本管线时生效；null 时由
 * NovelPageSource 用 displayMetrics 现算页面尺寸并套默认样式（黑底浅字 19f）。
 */
object ArchiveFactory {

    /**
     * 打开一本书的页面源（默认样式）。
     * 打开过程涉及压缩包头解析、条目枚举等阻塞 IO，调用方应在 IO 线程调用；
     * 返回的 [PageSource] 的 renderPage 内部已自行切换到 IO 线程。
     */
    fun open(context: Context, book: BookEntity): PageSource = open(context, book, null)

    /**
     * 打开一本书的页面源，可为文字小说指定排版样式（字色/字号/页面尺寸）。
     * style 仅对文本管线（TXT / 文本型 EPUB / 文本版 MOBI）生效；其余格式与 null 等价。
     * 调用方应在 IO 线程调用（同 [open]）。
     */
    fun open(context: Context, book: BookEntity, style: NovelStyle?): PageSource {
        if (book.uri.startsWith("webdav://")) return openRemote(context, book, style)
        if (book.uri.startsWith("gdrive://")) return openGdrive(context, book, style)
        val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
        return when (format) {
            BookFormat.TXT -> NovelPageSource.fromFile(context, book, style)
            BookFormat.CBZ -> ZipPageSource.open(context, book)
            BookFormat.EPUB ->
                openEpubTextOrFallback(
                    context, style,
                    openOwnedChannel = { openLocalChannel(context, book) },
                    fallback = { ZipPageSource.open(context, book) },
                )
            BookFormat.CBR -> RarPageSource.open(context, book)
            BookFormat.CB7 -> SevenZipPageSource.open(context, book)
            BookFormat.CBT -> TarPageSource.open(context, book)
            BookFormat.PDF -> PdfPageSource.open(context, book)
            BookFormat.MOBI, BookFormat.AZW3 ->
                openMobiTextOrFallback(
                    context, style,
                    openOwnedChannel = { openLocalChannel(context, book) },
                    fallback = { MobiPageSource.open(context, book) },
                )
            BookFormat.DIRECTORY -> DirectoryPageSource.open(context, book)
            BookFormat.PDF_FOLDER -> PdfFolderPageSource.open(context, book)
            BookFormat.UNKNOWN, null ->
                throw IllegalArgumentException("不支持的格式: ${book.format} ${book.uri}")
        }
    }

    /**
     * 打开 WebDAV 远程书（book.uri = "webdav://{configId}{absolutePath}"）。
     *
     * 调用方已在 IO 线程（见 [open] 的契约注释），这里的 runBlocking 只桥接一次
     * Room 查询（webDavConfig），阻塞为毫秒级，可接受。
     */
    private fun openRemote(context: Context, book: BookEntity, style: NovelStyle?): PageSource {
        val ref = parseWebDavUri(book.uri)
        val config = runBlocking {
            AppGraph.libraryRepository(context).webDavConfig(ref.configId)
        } ?: throw IllegalStateException("WebDAV 配置不存在，请检查远程仓库设置")
        val url = buildWebDavUrl(config.baseUrl, ref.rawPath)
        val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
        return when (format) {
            BookFormat.TXT ->
                NovelPageSource.fromWebDavText(context, book, url, config.username, config.password, style)
            BookFormat.CBZ ->
                RemoteArchiveSources.openRemoteZip(context, book, url, config.username, config.password)
            BookFormat.EPUB ->
                openEpubTextOrFallback(
                    context, style,
                    openOwnedChannel = {
                        val channel = HttpRangeChannel(url, config.username, config.password)
                        channel to { runCatching { channel.close() } }
                    },
                    fallback = {
                        RemoteArchiveSources.openRemoteZip(context, book, url, config.username, config.password)
                    },
                )
            BookFormat.CB7 ->
                RemoteArchiveSources.openRemoteSevenZip(context, book, url, config.username, config.password)
            BookFormat.CBT ->
                RemoteArchiveSources.openRemoteTar(context, book, url, config.username, config.password)
            BookFormat.CBR ->
                RemoteArchiveSources.openRemoteRar(context, book, url, config.username, config.password)
            BookFormat.PDF ->
                RemoteArchiveSources.openRemotePdf(context, book, url, config.username, config.password)
            BookFormat.MOBI, BookFormat.AZW3 ->
                openMobiTextOrFallback(
                    context, style,
                    openOwnedChannel = {
                        val channel = HttpRangeChannel(url, config.username, config.password)
                        channel to { runCatching { channel.close() } }
                    },
                    fallback = {
                        // HttpRangeChannel 由 MobiPageSource.openRemoteWebDav 内部创建，
                        // 打开失败时在其 try-catch 中回收（照 RemoteArchiveSources 的既有模式）
                        MobiPageSource.openRemoteWebDav(book, url, config.username, config.password)
                    },
                )
            BookFormat.DIRECTORY ->
                throw UnsupportedOperationException("远程图片目录暂不支持")
            BookFormat.PDF_FOLDER ->
                throw UnsupportedOperationException("远程 PDF 合集暂不支持")
            BookFormat.UNKNOWN, null ->
                throw IllegalArgumentException("不支持的格式: ${book.format} ${book.uri}")
        }
    }

    /**
     * 打开 Google Drive 远程书（book.uri = "gdrive://{accountId}/{fileId}"）。
     *
     * 调用方已在 IO 线程（见 [open] 的契约注释），这里的 runBlocking 只桥接一次
     * Room 查询（gdriveAccount，前置校验账号存在），阻塞为毫秒级，可接受。
     * 后续每次 HTTP 请求的 Bearer token 由动态鉴权提供者经 refreshGdriveAccessToken
     * 获取（内部缓存，未过期几乎零开销）。
     */
    private fun openGdrive(context: Context, book: BookEntity, style: NovelStyle?): PageSource {
        val ref = parseGdriveUri(book.uri)
        val account = runBlocking {
            AppGraph.libraryRepository(context).gdriveAccount(ref.accountId)
        } ?: throw IllegalStateException("Google Drive 账号不存在，请检查远程仓库设置")
        val format = runCatching { BookFormat.valueOf(book.format) }.getOrNull()
        return when (format) {
            BookFormat.TXT ->
                NovelPageSource.fromGdriveText(context, book, ref.fileId, account.id, style)
            BookFormat.CBZ ->
                RemoteGdriveSources.openGdriveZip(context, book, ref.fileId, account.id)
            BookFormat.EPUB ->
                openEpubTextOrFallback(
                    context, style,
                    openOwnedChannel = { gdriveOwnedChannel(context, ref.fileId, account.id) },
                    fallback = {
                        RemoteGdriveSources.openGdriveZip(context, book, ref.fileId, account.id)
                    },
                )
            BookFormat.CB7 ->
                RemoteGdriveSources.openGdriveSevenZip(context, book, ref.fileId, account.id)
            BookFormat.CBT ->
                RemoteGdriveSources.openGdriveTar(context, book, ref.fileId, account.id)
            BookFormat.CBR ->
                RemoteGdriveSources.openGdriveRar(context, book, ref.fileId, account.id)
            BookFormat.PDF ->
                RemoteGdriveSources.openGdrivePdf(context, book, ref.fileId, account.id)
            BookFormat.MOBI, BookFormat.AZW3 ->
                openMobiTextOrFallback(
                    context, style,
                    openOwnedChannel = { gdriveOwnedChannel(context, ref.fileId, account.id) },
                    fallback = {
                        // HttpRangeChannel（OAuth Bearer 动态鉴权）由 openRemoteGdrive 内部创建，
                        // 打开失败时在其 try-catch 中回收（照 RemoteGdriveSources 的既有模式）
                        MobiPageSource.openRemoteGdrive(context, book, ref.fileId, account.id)
                    },
                )
            BookFormat.DIRECTORY ->
                throw UnsupportedOperationException("Google Drive 暂不支持图片目录")
            BookFormat.PDF_FOLDER ->
                throw UnsupportedOperationException("Google Drive 暂不支持 PDF 合集")
            BookFormat.UNKNOWN, null ->
                throw IllegalArgumentException("不支持的格式: ${book.format} ${book.uri}")
        }
    }

    // ------------------------------------------------------------------
    // 文本管线（TXT / EPUB 文本版 / MOBI 文本版）的打开与回退
    // ------------------------------------------------------------------

    /**
     * EPUB 打开：先走文本管线探测（[NovelTextExtractor.parseEpub]，container → OPF →
     * spine → 首个 xhtml 查 <img>）——命中文本版则构造 [NovelPageSource]（文本全量进内存后
     * 立即释放 zip 与通道）；抛 [EpubImageBasedException]（首个内容文档含 <img> / spine 无
     * 文本条目）则回退 [fallback]（现有 ZipPageSource 图片管线，自行重新打开文件）。
     * 其余异常（结构损坏等）回收资源后原样抛出。
     *
     * openOwnedChannel 返回（随机读通道, 通道外资源的释放动作，如 SAF pfd）。
     */
    private fun openEpubTextOrFallback(
        context: Context,
        style: NovelStyle?,
        openOwnedChannel: () -> Pair<SeekableByteChannel, () -> Unit>,
        fallback: () -> PageSource,
    ): PageSource {
        val (channel, release) = openOwnedChannel()
        var zip: ZipFile? = null
        try {
            zip = ZipFile(channel)
            val data = NovelTextExtractor.parseEpub(zip)
            val source = NovelPageSource.open(
                context, data.paragraphs, data.marks, style, data.coverBytes, data.imageGroups,
            )
            runCatching { zip.close() }
            runCatching { channel.close() }
            runCatching { release() }
            return source
        } catch (e: EpubImageBasedException) {
            runCatching { zip?.close() }
            runCatching { channel.close() }
            runCatching { release() }
            return fallback()
        } catch (t: Throwable) {
            runCatching { zip?.close() }
            runCatching { channel.close() }
            runCatching { release() }
            throw t
        }
    }

    /**
     * MOBI/AZW3 打开：探测（[NovelTextExtractor.probeMobi] 只读 PDB 头 + record0）→
     * 压缩可支持（无压缩/PalmDOC）+ 无加密 + 无图片资源 → 文本管线；抛
     * [MobiNotTextException]（HUFF/CDIC 压缩、DRM、图片版、无文本记录）则回退
     * [fallback]（现有 MobiPageSource 图片管线，保留其 DRM/图片行为与错误提示）。
     */
    private fun openMobiTextOrFallback(
        context: Context,
        style: NovelStyle?,
        openOwnedChannel: () -> Pair<SeekableByteChannel, () -> Unit>,
        fallback: () -> PageSource,
    ): PageSource {
        val (channel, release) = openOwnedChannel()
        try {
            val info = NovelTextExtractor.probeMobi(channel)
            info.requireTextCandidate()
            val html = NovelTextExtractor.extractMobiHtml(channel, info)
            val (paragraphs, marks) = NovelTextExtractor.mobiHtmlToBook(html)
            val source = NovelPageSource.open(context, paragraphs, marks, style)
            runCatching { channel.close() }
            runCatching { release() }
            return source
        } catch (e: MobiNotTextException) {
            runCatching { channel.close() }
            runCatching { release() }
            return fallback()
        } catch (t: Throwable) {
            runCatching { channel.close() }
            runCatching { release() }
            throw t
        }
    }

    /** 本地随机读通道：file:// 或 / 开头路径直接开 FileChannel；content:// 走 SAF pfd */
    private fun openLocalChannel(
        context: Context,
        book: BookEntity,
    ): Pair<SeekableByteChannel, () -> Unit> {
        val local = PageSources.resolveLocalFile(book)
        if (local != null) {
            val channel = FileInputStream(local).channel
            return channel to { runCatching { channel.close() } }
        }
        val pfd = PageSources.openPfd(context, book)
        val channel = FileInputStream(pfd.fileDescriptor).channel
        return channel to { runCatching { pfd.close() } }
    }

    /** Google Drive 随机读通道：动态 Bearer 鉴权（token 会过期，逐请求刷新，内部缓存） */
    private fun gdriveOwnedChannel(
        context: Context,
        fileId: String,
        accountId: Long,
    ): Pair<SeekableByteChannel, () -> Unit> {
        val channel = HttpRangeChannel(
            GoogleDriveClient.downloadUrl(fileId), null, null,
            authHeaderProvider = {
                // 调用点全部在网络线程（Dispatchers.IO / OkHttp 工作线程），runBlocking 桥接安全；
                // token 只进请求头，绝不落日志
                val token = runBlocking {
                    AppGraph.libraryRepository(context).refreshGdriveAccessToken(accountId)
                }
                "Bearer $token"
            },
        )
        return channel to { runCatching { channel.close() } }
    }
}
