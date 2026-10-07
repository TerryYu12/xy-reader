package com.xyreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.documentfile.provider.DocumentFile
import com.xyreader.archive.PageSources
import com.xyreader.core.ArchiveFactory
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.LibraryRepository.ScanReport
import com.xyreader.core.LocalRepoEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 把仓库 tree URI 美化成短名字（与 ui 包的 prettyRepoName 同一规则，data 层独立实现避免反向依赖 ui）：
 * …/tree/primary%3AComics -> "Comics"
 */
internal fun prettyTreeName(uriString: String): String = runCatching {
    val uri = Uri.parse(uriString)
    val raw = uri.lastPathSegment ?: return@runCatching uriString
    val decoded = Uri.decode(raw).removePrefix("tree/")
    // primary:Comics / 1A2B-3C4D:Download/Comics 这类取冒号后的可读部分
    val name = decoded.substringAfter(':', missingDelimiterValue = decoded)
    name.substringAfterLast('/').ifBlank { decoded }
}.getOrDefault(uriString)

/**
 * 规则 c 的判定：一个文件夹直接子项里的 PDF 是否合并成一本「PDF 合集」（纯函数，便于单测）。
 * - [depth]：文件夹在仓库内的层级，仓库根为 0；根目录直接放的 PDF 不合并，仍各自成书；
 * - [enabled]：仓库开关 [LocalRepoEntity.mergeFolderPdfs]；
 * - [pdfCount]：直接子项里非隐藏 PDF 的个数，至少 2 个才合并，只有 1 个时仍是普通 PDF 书。
 */
internal fun shouldMergeFolderPdfs(depth: Int, enabled: Boolean, pdfCount: Int): Boolean =
    enabled && depth > 0 && pdfCount >= 2

/**
 * SAF 目录树扫描器：把树内的压缩包文件、图片目录与「PDF 合集」文件夹作为书籍入库，并逐本补齐页数与封面。
 * 逐目录的入库规则（见 [walk]）：
 * - a：可入库的压缩包/文档文件直接成书；
 * - b：直接子项图片数达标且不含压缩包/支持文件的目录，整个目录成一本图片目录书；
 * - c：子文件夹（非仓库根）直接包含 ≥2 个 PDF 且仓库开启 [LocalRepoEntity.mergeFolderPdfs] 时，
 *   整个文件夹成一本「PDF 合集」（每个 PDF 一章），这些 PDF 不再各自成书；其余格式不参与合并。
 * 两个入口：
 * - [scanRepo] 新主入口：以本地仓库为单位扫描 + 对账（新增/更新/移除）；
 * - [scan] 旧入口：兼容早期「按目录树增量入库」的调用链，内部派生为仓库扫描。
 * 封面/页数生成逻辑（[buildCoverAndMetadata]）同时被 WebDAV/GDrive 远程扫描器复用。
 */
class LibraryScanner(
    private val context: Context,
    private val dao: BookDao,
    /** 本地仓库 DAO；默认取全局单例，远程扫描器的两参构造调用自动可用 */
    private val repoDao: LocalRepoDao = ArkDatabase.getInstance(context).localRepoDao(),
) {

    /** 根为第 0 层，最深进入第 8 层子目录，防止异常深的树拖死扫描 */
    private val maxDepth = 8

    /** 目录直接子项图片数达到该值时，整个目录作为一本 DIRECTORY 书 */
    private val minImagesForDirectory = 2

    /** 视为图片的扩展名 */
    private val imageExts = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    /** 单本封面/页数获取的超时保护（CBR/PDF 打开会整包读取） */
    private val coverTimeoutMs = 60_000L

    /** 封面缓存目录 */
    private val coverDir: File
        get() = File(context.filesDir, "covers")

    /** 发现阶段的一条记录：待入库的实体草稿（未含自增 id）+ 命中封面约定的文件 uri */
    private data class Discovered(
        val entity: BookEntity,
        /** 目录书按封面约定命中的直接子项文件 uri；单文件书恒为 null */
        val conventionCoverUri: String? = null,
    )

    // ---------- 旧入口：兼容 RepoScanHandle 的 addRepository + scanTree 链路 ----------

    /**
     * 扫描一个已授权的目录树，新书籍入库。
     * 内部派生为仓库扫描：uri 已有对应仓库则复用，否则自动创建（名称取 uri 尾段美化）。
     * @return 本次新增的书籍数量
     */
    suspend fun scan(treeUriString: String): Int = withContext(Dispatchers.IO) {
        val repo = repoDao.getRepoByUri(treeUriString) ?: run {
            val id = repoDao.insertRepo(
                LocalRepoEntity(
                    name = prettyTreeName(treeUriString),
                    uri = treeUriString,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            repoDao.getRepoById(id) ?: return@withContext 0
        }
        scanRepo(repo).added
    }

    // ---------- 新主入口：扫描 + 对账 ----------

    /**
     * 扫描一个本地仓库并对账：
     * - 新发现的 uri → 入库（绑定仓库 id，defaultGroupId 非空时自动分组），计 added；
     * - 已入库的 uri → 比较 size/title，变化则更新记录（页数/封面不重算），计 updated；
     * - 库里有、本次未发现的书 → 删封面缓存文件 + 删记录，计 removed；
     * - 旧版本入库但未绑定仓库的遗留书按 uri 收编进本仓库，避免刷新后重复入库。
     * 根目录不可达（权限被吊销/存储被移除）时无法区分「文件真没了」与「暂不可读」，
     * 保守起见跳过本次扫描不做对账，避免误删整仓库的书。
     */
    suspend fun scanRepo(repo: LocalRepoEntity): ScanReport = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val treeUri = Uri.parse(repo.uri)
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanReport(0, 0, 0, System.currentTimeMillis() - start)

        // 基准集合：本仓库已入库的书（uri → 记录）
        val baseline = repoDao.getBooksOfRepo(repo.id).associateBy { it.uri }
        // 全库书籍快照：用于把旧版入库但未绑定仓库的遗留书按 uri 收编
        val allBooks = repoDao.getAllBooks().associateBy { it.uri }

        // 发现：递归遍历目录树，只收集不改库
        val discovered = mutableListOf<Discovered>()
        walk(root, treeUri, 0, repo, discovered, HashSet())

        var added = 0
        var updated = 0
        // 新插入的书（带自增 id）+ 封面约定文件 uri，扫描完成后统一补元数据
        val pendingMeta = mutableListOf<Pair<BookEntity, String?>>()
        val discoveredUris = HashSet<String>()

        for (item in discovered) {
            val uri = item.entity.uri
            // 同一 uri 本次只处理一次（防御异常遍历路径下的重复收集）
            if (!discoveredUris.add(uri)) continue
            val existing = baseline[uri]
            when {
                existing != null -> {
                    // 已在仓库：size/title 有变化则更新记录；页数/封面不重算
                    if (existing.size != item.entity.size || existing.title != item.entity.title) {
                        dao.updateBook(existing.copy(title = item.entity.title, size = item.entity.size))
                        updated++
                    }
                }
                allBooks.containsKey(uri) -> {
                    val orphan = allBooks.getValue(uri)
                    if (orphan.localRepoId == null) {
                        // 收编：旧版入库但未绑定仓库的书改绑到本仓库（不重复插行）
                        dao.updateBook(
                            orphan.copy(
                                localRepoId = repo.id,
                                // 用户手动分过组的书不打乱；未分组且仓库配置了默认分组才补
                                groupId = orphan.groupId ?: repo.defaultGroupId,
                            ),
                        )
                        updated++
                    }
                    // 属于其他仓库的同 uri 书：防御性跳过，不重复入库也不抢书
                }
                else -> {
                    // 全新书籍入库
                    val id = dao.insertBook(item.entity)
                    if (id > 0) {
                        added++
                        pendingMeta += item.entity.copy(id = id) to item.conventionCoverUri
                    }
                }
            }
        }

        // 对账移除：基准里有、本次没发现的书 → 删封面缓存文件 + 删记录
        var removed = 0
        for (book in baseline.values) {
            if (book.uri in discoveredUris) continue
            book.coverPath?.let { path -> runCatching { File(path).delete() } }
            dao.deleteById(book.id)
            removed++
        }

        // 新增的书逐本补页数 + 封面；单本失败/超时只跳过该书，不阻断整体
        for ((book, conventionCoverUri) in pendingMeta) {
            try {
                withTimeoutOrNull(coverTimeoutMs) {
                    buildCoverAndMetadata(book, conventionCoverUri)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 单本封面/页数失败不阻断扫描
            }
        }

        ScanReport(added, updated, removed, System.currentTimeMillis() - start)
    }

    // ---------- 封面 / 元数据 ----------

    /**
     * 生成一本书的封面与页数并写回数据库：
     * - 通过 ArchiveFactory 打开 PageSource，取 pageCount；
     * - 封面优先解码 [conventionCoverUri]（目录书封面约定命中文件），解码失败或为 null
     *   时回退 PageSource 第一页（renderCover）；
     * - 缩放到最大宽 512，JPEG 质量 82 存 filesDir/covers/{bookId}.jpg；
     * - 任何一步失败都返回 null，保留默认值，不影响书籍本身。
     * 签名保持单参兼容：WebDAV/GDrive 远程扫描器按此调用。
     * @return 封面文件绝对路径，失败为 null
     */
    suspend fun buildCoverAndMetadata(book: BookEntity): String? =
        buildCoverAndMetadata(book, conventionCoverUri = null)

    private suspend fun buildCoverAndMetadata(
        book: BookEntity,
        conventionCoverUri: String?,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val source = ArchiveFactory.open(context, book)
            try {
                val pageCount = source.pageCount
                // 约定封面文件优先；解码失败回退 PageSource 第一页
                val cover = conventionCoverUri
                    ?.let { uri -> runCatching { decodeConventionCover(uri) }.getOrNull() }
                    ?: source.renderCover().asAndroidBitmap().let { bmp ->
                        // 硬件位图无法压缩为 JPEG，先拷贝为软件位图
                        if (bmp.config == Bitmap.Config.HARDWARE) {
                            bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp
                        } else {
                            bmp
                        }
                    }
                val scaled = scaleToCoverWidth(cover)

                coverDir.mkdirs()
                val file = File(coverDir, "${book.id}.jpg")
                FileOutputStream(file).use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.flush()
                }

                // 重新读最新记录再更新，避免覆盖扫描期间产生的其他字段变化
                dao.getById(book.id)?.let { latest ->
                    dao.updateBook(latest.copy(totalPages = pageCount, coverPath = file.absolutePath))
                }
                file.absolutePath
            } finally {
                source.close()
            }
        } catch (e: Exception) {
            // 封面/页数失败不阻断入库
            null
        }
    }

    /**
     * 解码封面约定文件：两段式解码（先只读尺寸算采样率再真正解码），
     * 避免整张原图占满内存；openInputStream 两次打开由 contentResolver 保证一致。
     */
    private fun decodeConventionCover(uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // 采样到目标宽度一半以内即可，之后精确缩放补齐
        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= MAX_COVER_WIDTH) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
    }

    /** 缩放到最大宽 512，等比缩高 */
    private fun scaleToCoverWidth(cover: Bitmap): Bitmap =
        if (cover.width > MAX_COVER_WIDTH) {
            val height = (cover.height.toLong() * MAX_COVER_WIDTH / cover.width)
                .toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(cover, MAX_COVER_WIDTH, height, true)
        } else {
            cover
        }

    // ---------- 递归发现 ----------

    /**
     * 递归遍历目录树，把命中的书收集进 [discovered]（不做任何数据库写入）。
     * 去重：同一 document 只访问一次；同一 uri 只收集一次。
     */
    private suspend fun walk(
        dir: DocumentFile,
        treeUri: Uri,
        depth: Int,
        repo: LocalRepoEntity,
        discovered: MutableList<Discovered>,
        visitedDirs: MutableSet<String>,
    ) {
        // 防环：同一 document 只访问一次
        if (!visitedDirs.add(dir.uri.toString())) return

        val children = dir.listFiles()
        val subDirs = mutableListOf<DocumentFile>()
        var hasSupportedFile = false
        var imageCount = 0
        // 非隐藏 PDF（文件 + 文件名）：个数要遍历完才知道，是否合并留给下方规则 c 判定
        val pdfs = mutableListOf<Pair<DocumentFile, String>>()

        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                subDirs += child
            } else if (BookFormat.isSupportedFile(name)) {
                hasSupportedFile = true
                if (BookFormat.fromFileName(name) == BookFormat.PDF && !PageSources.isExcludedEntry(name)) {
                    pdfs += child to name
                } else {
                    // 规则 a：其余可入库的压缩包/文档文件（含隐藏 PDF）直接成书
                    discoverFileBook(child, name, dir, repo, discovered)
                }
            } else if (isImageFile(name)) {
                imageCount++
            }
        }

        // 规则 c：非根目录且 PDF ≥ 2 个（并且仓库开关打开）时整个文件夹合成一本「PDF 合集」；
        // 否则（仓库根 / 只有 1 个 / 开关关闭）每个 PDF 各自成书，与规则 a 一致
        if (shouldMergeFolderPdfs(depth, repo.mergeFolderPdfs, pdfs.size)) {
            discoverPdfFolderBook(dir, treeUri, pdfs.map { it.first }, children.toList(), repo, discovered)
        } else {
            for ((pdf, pdfName) in pdfs) {
                discoverFileBook(pdf, pdfName, dir, repo, discovered)
            }
        }

        // 规则 b：直接子项图片数达标、且不含压缩包/支持文件时，整个目录作为一本书
        if (imageCount >= minImagesForDirectory && !hasSupportedFile) {
            discoverDirectoryBook(dir, treeUri, children.toList(), repo, discovered)
        }

        // 递归子目录，限制深度
        if (depth + 1 <= maxDepth) {
            for (sub in subDirs) {
                walk(sub, treeUri, depth + 1, repo, discovered, visitedDirs)
            }
        }
    }

    /** 规则 a：单个压缩包/文档文件发现入库（单文件书不做封面文件名约定，第一页即封面） */
    private fun discoverFileBook(
        file: DocumentFile,
        name: String,
        parentDir: DocumentFile,
        repo: LocalRepoEntity,
        discovered: MutableList<Discovered>,
    ) {
        val entity = BookEntity(
            title = name.substringBeforeLast('.'),
            uri = file.uri.toString(),
            parentUri = parentDir.uri.toString(),
            isDirectory = false,
            format = BookFormat.fromFileName(name).name,
            size = file.length(),
            localRepoId = repo.id,
            groupId = repo.defaultGroupId,
            addedAt = System.currentTimeMillis(),
        )
        discovered += Discovered(entity)
    }

    /**
     * 规则 b：图片目录整目录发现入库。
     * 同时执行封面文件名约定：目录直接子项中存在「文件名去扩展名不区分大小写等于
     * repo.coverFileName 且扩展名为图片」的文件时，记下其 uri 供封面生成阶段优先使用。
     */
    private fun discoverDirectoryBook(
        dir: DocumentFile,
        treeUri: Uri,
        children: List<DocumentFile>,
        repo: LocalRepoEntity,
        discovered: MutableList<Discovered>,
    ) {
        val conventionCoverUri = findConventionCover(repo.coverFileName, children)

        val entity = BookEntity(
            title = dir.name ?: "",
            uri = folderDocumentUri(dir, treeUri),
            parentUri = null,
            isDirectory = true,
            format = BookFormat.DIRECTORY.name,
            size = 0L,
            localRepoId = repo.id,
            groupId = repo.defaultGroupId,
            addedAt = System.currentTimeMillis(),
        )
        discovered += Discovered(entity, conventionCoverUri)
    }

    /**
     * 规则 c：同文件夹多个 PDF 整夹发现入库为一本 PDF_FOLDER 书（每个 PDF 一章）。
     * 书名取文件夹名，uri 与图片目录书同形，size 为各 PDF 大小之和；
     * 封面文件名约定同样生效：文件夹里命中约定的图片优先于第一个 PDF 的首页作封面。
     */
    private fun discoverPdfFolderBook(
        dir: DocumentFile,
        treeUri: Uri,
        pdfFiles: List<DocumentFile>,
        children: List<DocumentFile>,
        repo: LocalRepoEntity,
        discovered: MutableList<Discovered>,
    ) {
        val conventionCoverUri = findConventionCover(repo.coverFileName, children)

        val entity = BookEntity(
            title = dir.name ?: "",
            uri = folderDocumentUri(dir, treeUri),
            parentUri = null,
            isDirectory = true,
            format = BookFormat.PDF_FOLDER.name,
            size = pdfFiles.sumOf { it.length() },
            localRepoId = repo.id,
            groupId = repo.defaultGroupId,
            addedAt = System.currentTimeMillis(),
        )
        discovered += Discovered(entity, conventionCoverUri)
    }

    /**
     * 文件夹书的 uri：用 tree + documentId 构造的 document URI，便于后续 ArchiveFactory 直接访问；
     * 构造失败时回退 [dir] 自身的 uri。图片目录书与 PDF 合集共用。
     */
    private fun folderDocumentUri(dir: DocumentFile, treeUri: Uri): String = runCatching {
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getDocumentId(dir.uri)).toString()
    }.getOrNull() ?: dir.uri.toString()

    /**
     * 封面文件名约定查找：直接子项中文件名（去扩展名、不区分大小写）等于
     * [coverFileName] 且扩展名为图片的文件；coverFileName 为空或无命中返回 null。
     */
    private fun findConventionCover(coverFileName: String, children: List<DocumentFile>): String? {
        if (coverFileName.isBlank()) return null
        return children.firstOrNull { child ->
            val name = child.name ?: return@firstOrNull false
            !child.isDirectory &&
                name.substringBeforeLast('.', missingDelimiterValue = "")
                    .equals(coverFileName, ignoreCase = true) &&
                isImageFile(name)
        }?.uri?.toString()
    }

    private fun isImageFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in imageExts

    private companion object {
        const val MAX_COVER_WIDTH = 512
        const val JPEG_QUALITY = 82
    }
}
