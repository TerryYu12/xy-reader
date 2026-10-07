package com.xyreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.BookGroupEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.GoogleDriveAccountEntity
import com.xyreader.core.LibraryRepository
import com.xyreader.core.LibraryRepository.ScanReport
import com.xyreader.core.LocalRepoEntity
import com.xyreader.core.ReaderPrefs
import com.xyreader.core.ShelfSection
import com.xyreader.core.SortOption
import com.xyreader.core.WebDavConfigEntity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min
import org.apache.commons.compress.archivers.zip.ZipFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 仓库列表的 DataStore（旧版存储，仅迁移期读取）。注意：同一个 preferences 文件全局只能
 * 存在一个 DataStore 实例，因此委托必须定义在文件顶层，且 [AppGraph] 保证
 * [LibraryRepositoryImpl] 全局单例。
 */
private val Context.repoDataStore by preferencesDataStore(name = "repositories")

/** DataStore 里存仓库 uri 集合的 key（迁移到 Room 后会清空） */
private val KEY_REPO_URIS = stringSetPreferencesKey("repo_uris")

/** 自定义封面最大宽度（与扫描生成封面一致，px） */
private const val CUSTOM_COVER_MAX_WIDTH = 512

/** 分组封面边长上限；先采样再缩放，避免解码超大原图占满堆内存。 */
private const val GROUP_COVER_MAX_WIDTH = 512
private const val GROUP_COVER_MAX_HEIGHT = 768

/**
 * core.LibraryRepository 的 data 层实现。
 * 所有 suspend 方法内部自行切 Dispatchers.IO。
 *
 * 本地仓库已从 DataStore uri 列表迁移到 Room local_repos 表：
 * 构造时在后台把旧 DataStore 数据一次性搬进 Room（幂等，搬完清空旧 key）。
 */
class LibraryRepositoryImpl(context: Context) : LibraryRepository {

    private val appContext = context.applicationContext

    private val database: ArkDatabase = ArkDatabase.getInstance(appContext)

    private val dao: BookDao = database.bookDao()

    private val localRepoDao: LocalRepoDao = database.localRepoDao()

    private val groupDao: GroupDao = database.groupDao()

    private val scanner = LibraryScanner(appContext, dao, localRepoDao)

    /** 阅读配置持久化；Impl 经 AppGraph 全局单例，ReaderPrefsStore 随之唯一 */
    private val readerPrefsStore = ReaderPrefsStore(appContext)

    private val webDavDao: WebDavDao = ArkDatabase.getInstance(appContext).webDavDao()

    private val webDavClient = WebDavClient()

    private val webDavScanner = WebDavScanner(dao, scanner)

    private val gdriveDao: GdriveDao = ArkDatabase.getInstance(appContext).gdriveDao()

    private val gdriveScanner = GoogleDriveScanner(dao)

    /** 后台一次性任务（DataStore 迁移）用作用域；SupervisorJob 防单个失败取消兄弟任务 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** DataStore → Room 一次性迁移的防重入标志 */
    @Volatile
    private var dataStoreMigrated = false

    init {
        scope.launch { migrateDataStoreReposIfNeeded() }
    }

    /** access token 内存缓存：accountId → (token, 过期时刻 epoch ms)；不持久化，重启后按需重取 */
    private data class CachedGdriveToken(val token: String, val expiresAt: Long)

    private val gdriveTokenCache = mutableMapOf<Long, CachedGdriveToken>()

    /** token 刷新互斥锁：多本书同时打开时只刷一次 */
    private val gdriveTokenMutex = Mutex()

    // ---------- 本地仓库（Room） ----------

    /** 全部本地仓库（按创建顺序）；声明在 books 之前，构造期初始化的书架流需要引用它 */
    override val localRepos: Flow<List<LocalRepoEntity>> = localRepoDao.observeRepos()

    /** 书架启停过滤：仓库被禁用时其书在书架隐藏；未绑定仓库的书（远程书等）不受影响 */
    private fun filterEnabled(flow: Flow<List<BookEntity>>): Flow<List<BookEntity>> =
        localRepos.combine(flow) { repos, list ->
            list.filter { book ->
                book.localRepoId == null ||
                    repos.firstOrNull { it.id == book.localRepoId }?.enabled != false
            }
        }

    override suspend fun addLocalRepo(uriString: String, name: String?): Long =
        withContext(Dispatchers.IO) {
            // 按 uri 幂等：同一目录重复添加（如旧 UI 的 addRepository + scanTree 链路）
            // 返回已有仓库 id，避免重复行导致 getRepoByUri 单行查询异常
            localRepoDao.getRepoByUri(uriString)?.let { return@withContext it.id }
            localRepoDao.insertRepo(
                LocalRepoEntity(
                    name = name?.takeIf { it.isNotBlank() } ?: prettyTreeName(uriString),
                    uri = uriString,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    override suspend fun renameLocalRepo(id: Long, name: String) {
        withContext(Dispatchers.IO) { localRepoDao.renameRepo(id, name) }
    }

    /** 开关：禁用后其书在书架隐藏且不参与刷新 */
    override suspend fun setLocalRepoEnabled(id: Long, enabled: Boolean) {
        withContext(Dispatchers.IO) { localRepoDao.setEnabled(id, enabled) }
    }

    /** 配置仓库：名称 / 封面文件名约定 / 默认添加分组（null = 不自动分组）/ 同文件夹 PDF 合并开关 */
    override suspend fun updateLocalRepoConfig(
        id: Long,
        name: String,
        coverFileName: String,
        defaultGroupId: Long?,
        mergeFolderPdfs: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            localRepoDao.updateConfig(id, name, coverFileName, defaultGroupId, mergeFolderPdfs)
        }
    }

    /** 删除仓库及其入库的书（含封面缓存文件）；授权的 SAF 权限无法主动撤销 */
    override suspend fun removeLocalRepo(id: Long) {
        withContext(Dispatchers.IO) {
            val books = localRepoDao.getBooksOfRepo(id)
            for (book in books) {
                // 先删封面缓存文件，再删库记录
                book.coverPath?.let { path -> runCatching { File(path).delete() } }
                dao.deleteById(book.id)
            }
            localRepoDao.deleteRepoById(id)
        }
    }

    /** 刷新单个仓库：重新扫描 + 对账（新增/更新/移除） */
    override suspend fun scanLocalRepo(id: Long): ScanReport = withContext(Dispatchers.IO) {
        val repo = localRepoDao.getRepoById(id) ?: throw IllegalArgumentException("仓库不存在")
        if (!repo.enabled) throw IOException("仓库已停用，请先开启")
        scanner.scanRepo(repo)
    }

    // ---------- 书籍流 ----------

    /** 全部书籍（默认按添加时间倒序），已过滤停用仓库的书 */
    override val books: Flow<List<BookEntity>> = filterEnabled(dao.observeBooks(""))

    override val bookmarks: Flow<List<BookmarkEntity>> = dao.observeBookmarks()

    override fun books(section: ShelfSection, sort: SortOption, query: String): Flow<List<BookEntity>> =
        when (section) {
            ShelfSection.ALL -> when (sort) {
                SortOption.TITLE -> filterEnabled(dao.observeAllByTitle(query))
                SortOption.ADDED -> filterEnabled(dao.observeAllByAdded(query))
                SortOption.RECENT_READ -> filterEnabled(dao.observeAllByRecentRead(query))
                SortOption.UNREAD -> filterEnabled(dao.observeAllByUnread(query))
            }
            // 收藏/历史区的 DAO 查询自带默认排序，其余排序方式在内存中补排
            ShelfSection.FAVORITE -> filterEnabled(dao.observeFavorites(query)).map { sortLocally(it, sort) }
            ShelfSection.HISTORY -> filterEnabled(dao.observeHistory(query)).map { sortLocally(it, sort) }
            // 书签区不在 books 里体现，书签列表走 bookmarks Flow
            ShelfSection.BOOKMARK -> filterEnabled(dao.observeBooks(query))
        }

    // ---------- DataStore → Room 一次性迁移 ----------

    /**
     * 旧版仓库列表存 DataStore（uri 集合），新版存 Room local_repos 表。
     * 构造后异步执行一次：local_repos 为空且有旧数据时逐个建仓库，随后清空旧 key。
     * 整体吞异常：迁移失败不崩溃，下次启动可重试；旧 key 一经读取处理即清空，保证幂等。
     */
    private suspend fun migrateDataStoreReposIfNeeded() {
        if (dataStoreMigrated) return
        dataStoreMigrated = true
        runCatching {
            val oldUris = appContext.repoDataStore.data.first()[KEY_REPO_URIS].orEmpty()
            if (oldUris.isEmpty()) return
            // 表非空说明已迁移过（或用户已建新仓库），只清旧 key
            if (localRepoDao.observeRepos().first().isEmpty()) {
                for (uri in oldUris) {
                    runCatching { addLocalRepo(uri, null) }
                    // 单个失败不中断其余
                }
            }
            appContext.repoDataStore.edit { prefs -> prefs[KEY_REPO_URIS] = emptySet() }
        }
    }

    // ---------- 扫描 / 封面 ----------

    override suspend fun scanTree(treeUriString: String): Int = scanner.scan(treeUriString)

    // ---------- 外部打开 / 分享导入 ----------

    /** 导入文件的私有目录：filesDir/imported（卸载应用时一并清理） */
    private val importedDir: File
        get() = File(appContext.filesDir, "imported")

    /**
     * 外部「用其他应用打开 / 分享」导入单文件。
     * 显示名解析顺序：发送方 hint → ContentResolver DISPLAY_NAME → uri 尾段 → 时间戳兜底；
     * 无扩展名时用 MIME 推一个；仍不可识别则拒绝。拷贝到私有目录后入库并补封面/页数。
     * 同一文件（同名）重复导入：覆盖文件内容、复用已有记录返回原 id。
     */
    override suspend fun importSharedFile(
        uriString: String,
        nameHint: String?,
        mimeType: String?,
    ): Long = withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val resolver = appContext.contentResolver

        val displayName = nameHint?.takeIf { it.isNotBlank() }
            ?: runCatching {
                resolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            ?: "shared_${System.currentTimeMillis()}"

        var workName = sanitizeSharedFileName(displayName)
        if (BookFormat.fromFileName(workName) == BookFormat.UNKNOWN) {
            sharedMimeToExtension(mimeType)?.let { ext -> workName = "$workName.$ext" }
        }

        // —— 拷贝到私有目录（先落盘，类型判定最后可拿真实文件做魔数兜底） ——
        importedDir.mkdirs()
        var target = File(importedDir, workName)
        val input = try {
            resolver.openInputStream(uri)
        } catch (e: SecurityException) {
            throw IOException("无法读取文件（权限被拒绝，文件管理器可能未授权）", e)
        } ?: throw IOException("无法读取文件（内容提供方不可访问）")
        input.use { ins ->
            FileOutputStream(target).use { out ->
                ins.copyTo(out)
                out.flush()
            }
        }

        // —— 类型判定链：名称扩展名 → MIME 兜底 → 文件头魔数嗅探 ——
        var format = BookFormat.fromFileName(workName)
        if (format == BookFormat.UNKNOWN) {
            val sniffed = runCatching { sniffBookExtension(target) }.getOrNull()
            if (sniffed != null) {
                val renamed = File(importedDir, "$workName.$sniffed")
                if (target.renameTo(renamed)) {
                    workName = "$workName.$sniffed"
                    target = renamed
                    format = BookFormat.fromFileName(workName)
                }
            }
        }
        if (format == BookFormat.UNKNOWN) {
            runCatching { target.delete() }
            throw IllegalArgumentException(
                "无法识别文件类型（名称：$displayName，类型：${mimeType ?: "未知"}）",
            )
        }

        val targetUri = Uri.fromFile(target).toString()
        dao.getByUri(targetUri)?.let { existing ->
            // 重复导入：文件已覆盖更新，记录只刷新大小
            dao.updateBook(existing.copy(size = target.length()))
            return@withContext existing.id
        }

        val id = dao.insertBook(
            BookEntity(
                title = workName.substringBeforeLast('.').ifBlank { workName },
                uri = targetUri,
                format = format.name,
                size = target.length(),
                addedAt = System.currentTimeMillis(),
            ),
        )
        if (id > 0) {
            // 补页数与封面；失败不阻断导入（与扫描管线同一策略）
            runCatching {
                dao.getById(id)?.let { saved -> scanner.buildCoverAndMetadata(saved) }
            }
        }
        id
    }

    /** 文件名清洗：去掉路径分隔符等非法字符，保留中文；超长截断时保留扩展名 */
    private fun sanitizeSharedFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\u0000-\u001f]"), "_")
            .trim()
        if (cleaned.isBlank()) return "shared_${System.currentTimeMillis()}"
        if (cleaned.length <= 80) return cleaned
        // 截断不能吃掉扩展名，否则会被误判成不支持的类型
        val ext = cleaned.substringAfterLast('.', "")
        val suffix = if (ext.isNotEmpty() && ext.length <= 10) ".$ext" else ""
        return cleaned.take(80 - suffix.length) + suffix
    }

    /** 无扩展名时的 MIME → 扩展名兜底映射（与清单过滤器覆盖的格式一致） */
    private fun sharedMimeToExtension(mimeType: String?): String? =
        when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
            "text/plain" -> "txt"
            "application/pdf" -> "pdf"
            "application/epub+zip" -> "epub"
            "application/x-mobipocket-ebook" -> "mobi"
            "application/vnd.amazon.ebook" -> "azw3"
            "application/x-mobi8-ebook" -> "azw3"
            "application/vnd.comicbook+zip" -> "cbz"
            "application/vnd.comicbook-rar" -> "cbr"
            "application/x-cbz" -> "cbz"
            "application/x-cbr" -> "cbr"
            "application/zip" -> "zip"
            "application/x-7z-compressed" -> "7z"
            "application/x-rar-compressed" -> "cbr"
            "application/vnd.rar" -> "cbr"
            "application/x-tar" -> "tar"
            "application/x-zip-compressed" -> "zip"
            else -> null
        }

    /**
     * 文件头魔数嗅探：名称与 MIME 都定不了类型时的最后兜底，返回扩展名（识别不了为 null）。
     * 覆盖：%PDF→pdf；PK→epub（mimetype 条目）/zip；Rar!→rar；7z 魔数→7z；
     * PDB/BOOKMOBI→mobi；前 1KB 无 NUL 字节→txt。
     */
    private fun sniffBookExtension(file: File): String? {
        val head = ByteArray(1024)
        val len = file.inputStream().use { it.read(head) }
        if (len <= 0) return null
        fun byteAt(i: Int) = if (i < len) head[i].toInt() and 0xFF else -1
        return when {
            len >= 4 && byteAt(0) == 0x25 && byteAt(1) == 0x50 &&
                byteAt(2) == 0x44 && byteAt(3) == 0x46 -> "pdf" // %PDF
            len >= 4 && byteAt(0) == 0x50 && byteAt(1) == 0x4B ->
                if (zipLooksLikeEpub(file)) "epub" else "zip" // PK\x03\x04
            byteAt(0) == 0x52 && byteAt(1) == 0x61 &&
                byteAt(2) == 0x72 && byteAt(3) == 0x21 -> "rar" // Rar!
            len >= 4 && byteAt(0) == 0x37 && byteAt(1) == 0x7A &&
                byteAt(2) == 0xBC && byteAt(3) == 0xAF -> "7z"
            len >= 68 && String(head, 60, 8, Charsets.US_ASCII) in setOf("BOOKMOBI", "TEXtREAd") -> "mobi"
            (0 until len).none { head[it] == 0.toByte() } -> "txt"
            else -> null
        }
    }

    /** zip 家族判定：含 mimetype 条目（EPUB 规范首条目）→ epub；读取失败按普通 zip 处理 */
    private fun zipLooksLikeEpub(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("mimetype") != null ||
                zip.entries.asSequence().take(5).any { it.name == "mimetype" }
        }
    }.getOrDefault(false)

    override suspend fun ensureCover(book: BookEntity): String? = withContext(Dispatchers.IO) {
        val cached = book.coverPath
        if (cached != null && File(cached).exists()) {
            cached
        } else {
            // 重新生成封面与页数并写回数据库
            scanner.buildCoverAndMetadata(book)
        }
    }

    // ---------- 进度 / 收藏 / 删除 ----------

    override suspend fun saveProgress(bookId: Long, page: Int, totalPages: Int) {
        withContext(Dispatchers.IO) {
            dao.updateProgress(bookId, page, totalPages, System.currentTimeMillis())
        }
    }

    override suspend fun toggleFavorite(bookId: Long) {
        withContext(Dispatchers.IO) {
            val current = dao.getById(bookId) ?: return@withContext
            dao.setFavorite(bookId, !current.isFavorite)
        }
    }

    override suspend fun deleteBook(bookId: Long) {
        withContext(Dispatchers.IO) {
            val coverPath = dao.getById(bookId)?.coverPath
            database.withTransaction {
                dao.deleteBookmarksByBookId(bookId)
                dao.deleteById(bookId)
            }
            coverPath?.let { path -> runCatching { File(path).delete() } }
        }
    }

    override suspend fun renameBook(bookId: Long, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        withContext(Dispatchers.IO) {
            dao.getById(bookId)?.let { dao.updateBook(it.copy(title = clean)) }
        }
    }

    override suspend fun clearReadingHistory(bookId: Long) {
        withContext(Dispatchers.IO) {
            dao.getById(bookId)?.let { dao.updateBook(it.copy(currentPage = 0, lastReadAt = null)) }
        }
    }

    override suspend fun refreshCover(bookId: Long): Boolean = withContext(Dispatchers.IO) {
        val book = dao.getById(bookId) ?: return@withContext false
        val old = book.coverPath
        // 走常规生成链（附带回填页数）；失败则保留旧封面不动
        val generated = runCatching { scanner.buildCoverAndMetadata(book) }.getOrNull()
            ?: return@withContext false
        val src = File(generated)
        if (!src.exists()) return@withContext false
        // 换个带时间戳的文件名：同一路径换内容时图片库（Coil）可能命中旧缓存
        val target = File(src.parentFile, "${bookId}-r${System.currentTimeMillis()}.jpg")
        val moved = runCatching { src.renameTo(target) }.getOrDefault(false)
        val finalPath = if (moved) target else src
        dao.getById(bookId)?.let { latest ->
            dao.updateBook(latest.copy(coverPath = finalPath.absolutePath))
        }
        if (old != null && old != finalPath.absolutePath) {
            runCatching { File(old).delete() }
        }
        true
    }

    override suspend fun setCustomCover(bookId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val book = dao.getById(bookId) ?: return@withContext false
        val bitmap = runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return@withContext false
        // 缩放到与扫描生成封面同宽，JPEG 质量 88
        val scaled = if (bitmap.width > CUSTOM_COVER_MAX_WIDTH) {
            val height = (bitmap.height.toLong() * CUSTOM_COVER_MAX_WIDTH / bitmap.width)
                .toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, CUSTOM_COVER_MAX_WIDTH, height, true)
        } else {
            bitmap
        }
        val dir = File(appContext.filesDir, "covers").apply { mkdirs() }
        val out = File(dir, "${bookId}-custom-${System.currentTimeMillis()}.jpg")
        val written = runCatching {
            FileOutputStream(out).use { stream ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                stream.flush()
            }
        }.isSuccess
        if (!written) return@withContext false
        book.coverPath?.let { path -> runCatching { File(path).delete() } }
        dao.updateBook(book.copy(coverPath = out.absolutePath))
        true
    }

    // ---------- 书签 ----------

    override suspend fun addBookmark(bookId: Long, pageIndex: Int) {
        withContext(Dispatchers.IO) {
            dao.insertBookmark(
                BookmarkEntity(
                    bookId = bookId,
                    pageIndex = pageIndex,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun removeBookmark(bookmarkId: Long) {
        withContext(Dispatchers.IO) {
            dao.deleteBookmark(bookmarkId)
        }
    }

    // ---------- 本地仓库管理（旧 API，派生自 Room 仓库） ----------

    /** 已添加的仓库 uri 列表（旧 UI 用）：从 Room 仓库流派生，Set 无序 sorted 保持旧观感 */
    override val repositories: Flow<List<String>> =
        localRepos.map { repos -> repos.map { it.uri }.sorted() }

    override suspend fun addRepository(uriString: String) {
        addLocalRepo(uriString, null)
    }

    override suspend fun removeRepository(uriString: String) {
        withContext(Dispatchers.IO) {
            localRepoDao.getRepoByUri(uriString)?.let { removeLocalRepo(it.id) }
        }
    }

    // ---------- WebDAV 远程仓库 ----------

    override val webdavConfigs: Flow<List<WebDavConfigEntity>> = webDavDao.observeConfigs()

    /** archive 层按 configId 解析凭据（远程书打开时调用），保持轻量 */
    override suspend fun webDavConfig(id: Long): WebDavConfigEntity? =
        withContext(Dispatchers.IO) { webDavDao.getConfigById(id) }

    override suspend fun addWebDavConfig(name: String, baseUrl: String, username: String, password: String): Long =
        withContext(Dispatchers.IO) {
            webDavDao.insertConfig(
                WebDavConfigEntity(
                    name = name,
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

    override suspend fun removeWebDavConfig(id: Long) {
        withContext(Dispatchers.IO) { webDavDao.deleteConfigById(id) }
    }

    /** 测试连通性，null 表示成功（客户端在 IO 线程同步调用） */
    override suspend fun testWebDav(baseUrl: String, username: String, password: String): String? =
        withContext(Dispatchers.IO) { webDavClient.test(baseUrl, username, password) }

    override suspend fun scanWebDav(configId: Long): Int = withContext(Dispatchers.IO) {
        val config = webDavDao.getConfigById(configId) ?: throw IllegalArgumentException("配置不存在")
        webDavScanner.scan(config, configId)
    }

    // ---------- Google Drive（Google One） ----------

    override val gdriveAccounts: Flow<List<GoogleDriveAccountEntity>> = gdriveDao.observeAccounts()

    override suspend fun addGdriveAccount(
        name: String,
        clientId: String,
        clientSecret: String,
        folderId: String,
        refreshToken: String,
    ): Long = withContext(Dispatchers.IO) {
        gdriveDao.insertAccount(
            GoogleDriveAccountEntity(
                name = name,
                clientId = clientId.trim(),
                clientSecret = clientSecret.trim(),
                refreshToken = refreshToken,
                folderId = folderId.trim(),
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun removeGdriveAccount(id: Long) {
        withContext(Dispatchers.IO) {
            gdriveTokenCache.remove(id)
            gdriveDao.deleteAccountById(id)
        }
    }

    /** archive 层按 accountId 解析授权信息（远程书打开时调用），保持轻量 */
    override suspend fun gdriveAccount(id: Long): GoogleDriveAccountEntity? =
        withContext(Dispatchers.IO) { gdriveDao.getAccountById(id) }

    /**
     * 返回有效的 access token：内存缓存命中（过期前 60 秒余量）直接返回；
     * 失效则查库取 refresh_token 换新。invalid_grant/401 归类为"授权已失效"提示重新授权。
     */
    override suspend fun refreshGdriveAccessToken(accountId: Long): String =
        withContext(Dispatchers.IO) {
            gdriveTokenMutex.withLock {
                val now = System.currentTimeMillis()
                gdriveTokenCache[accountId]?.takeIf { now < it.expiresAt - 60_000 }?.token
                    ?: run {
                        val account = gdriveDao.getAccountById(accountId)
                            ?: throw IllegalArgumentException("Google Drive 账号不存在")
                        if (account.refreshToken.isBlank()) {
                            throw IOException("尚未授权，请先完成授权")
                        }
                        val info = try {
                            GoogleDriveClient.refreshToken(
                                account.clientId,
                                account.clientSecret,
                                account.refreshToken,
                            )
                        } catch (e: GDriveHttpException) {
                            // 401/invalid_grant/invalid_client 都意味着 refresh_token 失效
                            throw IOException("Google 授权已失效，请重新授权: ${e.message}", e)
                        }
                        val entry = CachedGdriveToken(
                            token = info.accessToken,
                            expiresAt = now + info.expiresInSeconds * 1000,
                        )
                        gdriveTokenCache[accountId] = entry
                        entry.token
                    }
            }
        }

    override suspend fun scanGdrive(accountId: Long): Int = withContext(Dispatchers.IO) {
        val account = gdriveDao.getAccountById(accountId)
            ?: throw IllegalArgumentException("账号不存在")
        if (account.refreshToken.isBlank()) {
            throw IOException("尚未授权，请先完成授权")
        }
        gdriveScanner.scan(account, appContext)
    }

    // ---------- 书架分组 ----------

    override val groups: Flow<List<BookGroupEntity>> = groupDao.observeGroups()

    override suspend fun addGroup(name: String): Long = withContext(Dispatchers.IO) {
        groupDao.insertGroup(
            BookGroupEntity(
                name = name,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun renameGroup(id: Long, name: String) {
        withContext(Dispatchers.IO) {
            groupDao.updateGroupName(id, name)
        }
    }

    override suspend fun setGroupCover(groupId: Long, path: String?): Boolean = withContext(Dispatchers.IO) {
        if (groupDao.getGroupById(groupId) == null) return@withContext false
        if (path != null && !File(path).isFile) return@withContext false
        groupDao.updateGroupCover(groupId, path) > 0
    }

    override suspend fun importGroupCover(groupId: Long, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        if (groupDao.getGroupById(groupId) == null) return@withContext false
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsRead = try {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
                bounds.outWidth > 0 && bounds.outHeight > 0
            } == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!boundsRead) {
            return@withContext false
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = groupCoverSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return@withContext false

        var scaledBitmap: Bitmap? = null
        var outputFile: File? = null
        var coverCommitted = false
        try {
            val scale = min(
                1f,
                min(
                    GROUP_COVER_MAX_WIDTH.toFloat() / bitmap.width,
                    GROUP_COVER_MAX_HEIGHT.toFloat() / bitmap.height,
                ),
            )
            val output = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    max(1, (bitmap.width * scale).toInt()),
                    max(1, (bitmap.height * scale).toInt()),
                    true,
                ).also { scaledBitmap = it }
            } else {
                bitmap
            }
            val directory = File(appContext.filesDir, "group_covers")
            if (!directory.exists() && !directory.mkdirs()) return@withContext false
            val newFile = File.createTempFile("g$groupId-", ".jpg", directory)
            outputFile = newFile
            val compressed = FileOutputStream(newFile).use { stream ->
                val success = output.compress(Bitmap.CompressFormat.JPEG, 88, stream)
                stream.flush()
                success
            }
            if (!compressed) return@withContext false

            // 组可能在图片处理过程中已被删除；只有数据库成功更新后才公布新路径。
            coverCommitted = groupDao.updateGroupCover(groupId, newFile.absolutePath) > 0
            coverCommitted
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        } finally {
            if (!coverCommitted) outputFile?.let { if (it.exists()) it.delete() }
            scaledBitmap?.recycle()
            bitmap.recycle()
        }
    }

    /** 删除分组：组内书先全部回到未分组（groupId 置空），再删分组本身，不删书 */
    override suspend fun removeGroup(id: Long) {
        withContext(Dispatchers.IO) {
            database.withTransaction {
                groupDao.clearBooksOfGroup(id)
                groupDao.deleteGroupById(id)
            }
        }
    }

    /** 把书移入分组；groupId 传 null 表示移出分组 */
    override suspend fun moveBookToGroup(bookId: Long, groupId: Long?) {
        withContext(Dispatchers.IO) {
            groupDao.moveBookToGroup(bookId, groupId)
        }
    }

    /**
     * 分组内的书；groupId 为 null 表示全部未分组的书。
     * DAO 默认按 addedAt 倒序，其余排序在内存补排；同样应用仓库启停过滤。
     */
    override fun booksInGroup(groupId: Long?, sort: SortOption): Flow<List<BookEntity>> {
        val base = if (groupId == null) {
            groupDao.observeUngroupedBooks()
        } else {
            groupDao.observeGroupBooks(groupId)
        }
        return filterEnabled(base).map { sortLocally(it, sort) }
    }

    // ---------- 阅读配置（DataStore） ----------

    override val readerPrefs: Flow<ReaderPrefs> = readerPrefsStore.prefs

    override suspend fun setReaderPrefs(prefs: ReaderPrefs) {
        withContext(Dispatchers.IO) {
            readerPrefsStore.set(prefs)
        }
    }

    // ---------- 私有辅助 ----------

    /** 收藏/历史区在内存中按 [SortOption] 补排，语义与 ALL 区的 SQL 排序保持一致 */
    private fun sortLocally(list: List<BookEntity>, sort: SortOption): List<BookEntity> =
        when (sort) {
            SortOption.TITLE -> list.sortedBy { it.title.lowercase() }
            SortOption.ADDED -> list.sortedByDescending { it.addedAt }
            // lastReadAt 为空的排最后
            SortOption.RECENT_READ -> list.sortedByDescending { it.lastReadAt ?: Long.MIN_VALUE }
            SortOption.UNREAD -> list.sortedWith(
                compareBy<BookEntity> { if (it.currentPage == 0 && it.totalPages > 0) 0 else 1 }
                    .thenByDescending { it.addedAt },
            )
        }
}

private fun groupCoverSampleSize(width: Int, height: Int): Int {
    var sampleSize = 1
    while (
        width / sampleSize > GROUP_COVER_MAX_WIDTH * 2 ||
        height / sampleSize > GROUP_COVER_MAX_HEIGHT * 2
    ) {
        if (sampleSize > Int.MAX_VALUE / 2) break
        sampleSize *= 2
    }
    return sampleSize
}
