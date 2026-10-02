package com.xyreader.core

import android.net.Uri
import kotlinx.coroutines.flow.Flow

/** 书库排序方式 */
enum class SortOption(val label: String) {
    RECENT_READ("最近阅读"),
    ADDED("添加时间"),
    TITLE("标题"),
    UNREAD("未读优先"),
}

/** 书架分区（对应 MH-ARK 书架页的四个入口） */
enum class ShelfSection(val label: String) {
    ALL("全部"),
    FAVORITE("收藏"),
    HISTORY("历史"),
    BOOKMARK("书签"),
}

/**
 * 书库仓库统一 API。data 层实现，UI/阅读器层调用。
 * 全部方法可在任意线程调用（实现内部切 IO）。
 */
interface LibraryRepository {
    /** 全部书籍（默认按添加时间倒序） */
    val books: Flow<List<BookEntity>>

    /** 按指定方式排序的书籍流（HISTORY = 有阅读记录按 lastReadAt 倒序；FAVORITE = 仅收藏） */
    fun books(section: ShelfSection, sort: SortOption, query: String): Flow<List<BookEntity>>

    /** 全部书签（按创建时间倒序） */
    val bookmarks: Flow<List<BookmarkEntity>>

    /** 扫描一个已授权的目录树，新书籍入库（自动生成页数与封面）。返回新增数量。 */
    suspend fun scanTree(treeUriString: String): Int

    /**
     * 外部「用其他应用打开 / 分享」导入单文件：把 content/file Uri 拷进应用私有目录并入库。
     * 同一文件重复导入复用已有记录（内容覆盖更新，返回原 id）。
     * @return 书籍 id；扩展名不受支持且 MIME 无法判定时抛 IllegalArgumentException
     */
    suspend fun importSharedFile(uriString: String, nameHint: String?, mimeType: String?): Long

    /** 生成封面（若已有缓存直接返回路径） */
    suspend fun ensureCover(book: BookEntity): String?

    /** 保存阅读进度（阅读器翻页时调用） */
    suspend fun saveProgress(bookId: Long, page: Int, totalPages: Int)

    suspend fun toggleFavorite(bookId: Long)

    suspend fun deleteBook(bookId: Long)

    /** 重命名书籍（空标题忽略） */
    suspend fun renameBook(bookId: Long, title: String)

    /** 删除阅读记录：进度与最近阅读清零（书与书签保留） */
    suspend fun clearReadingHistory(bookId: Long)

    /** 刷新封面：重新从书文件生成并替换缓存（同时刷新页数）。返回是否成功 */
    suspend fun refreshCover(bookId: Long): Boolean

    /** 自定义封面：把 [uri] 指向的图片写入封面缓存并替换。返回是否成功 */
    suspend fun setCustomCover(bookId: Long, uri: android.net.Uri): Boolean

    suspend fun addBookmark(bookId: Long, pageIndex: Int)

    suspend fun removeBookmark(bookmarkId: Long)

    /** 已添加的本地仓库目录 URI 列表（设置页-本地仓库管理用） */
    val repositories: Flow<List<String>>

    suspend fun addRepository(uriString: String)

    suspend fun removeRepository(uriString: String)

    // ---- WebDAV 远程仓库 ----

    /** 全部 WebDAV 配置 */
    val webdavConfigs: Flow<List<WebDavConfigEntity>>

    /** archive 层按 configId 解析凭据（远程书打开时用） */
    suspend fun webDavConfig(id: Long): WebDavConfigEntity?

    suspend fun addWebDavConfig(name: String, baseUrl: String, username: String, password: String): Long

    suspend fun removeWebDavConfig(id: Long)

    /** 测试连通性。返回 null 表示成功，否则返回错误描述（认证失败/路径不存在/网络错误等） */
    suspend fun testWebDav(baseUrl: String, username: String, password: String): String?

    /** 扫描一个 WebDAV 配置指向的目录树，远程书入库（含封面）。返回新增数量。 */
    suspend fun scanWebDav(configId: Long): Int

    // ---- Google Drive（Google One）----

    /** 全部 Google Drive 账号 */
    val gdriveAccounts: Flow<List<GoogleDriveAccountEntity>>

    /** 保存一个已授权的账号（refreshToken 由 data 层 GoogleDriveAuth.authorize 获得） */
    suspend fun addGdriveAccount(
        name: String,
        clientId: String,
        clientSecret: String,
        folderId: String,
        refreshToken: String,
    ): Long

    suspend fun removeGdriveAccount(id: Long)

    /** archive 层按 accountId 解析授权信息（远程书打开时用） */
    suspend fun gdriveAccount(id: Long): GoogleDriveAccountEntity?

    /** 返回有效的 access token（内部缓存，过期自动用 refresh_token 续期；并发安全） */
    suspend fun refreshGdriveAccessToken(accountId: Long): String

    /** 扫描账号目标文件夹（folderId 为空扫整个 My Drive 根），远程书入库。返回新增数量。 */
    suspend fun scanGdrive(accountId: Long): Int

    // ---- 书架分组 ----

    /** 全部分组（按创建顺序） */
    val groups: Flow<List<BookGroupEntity>>

    suspend fun addGroup(name: String): Long

    suspend fun renameGroup(id: Long, name: String)

    /** 设置或清除自定义封面路径；清除或替换时保留旧文件，避免误删已有用户数据。 */
    suspend fun setGroupCover(groupId: Long, path: String?): Boolean

    /** 从 SAF 图片 URI 导入分组封面到应用私有目录。 */
    suspend fun importGroupCover(groupId: Long, uri: Uri): Boolean

    /** 删除分组；组内书自动回到未分组（不删书） */
    suspend fun removeGroup(id: Long)

    /** 把书移入分组；groupId 传 null 表示移出分组 */
    suspend fun moveBookToGroup(bookId: Long, groupId: Long?)

    /** 某个分组的书；groupId 传 null 表示全部未分组的书 */
    fun booksInGroup(groupId: Long?, sort: SortOption): Flow<List<BookEntity>>

    // ---- 本地仓库（Room 表；替代早期 DataStore uri 列表，支持命名/开关/刷新/封面约定/默认分组） ----

    /** 一次扫描的结果报告（扫描报告弹层用） */
    data class ScanReport(
        val added: Int,
        val updated: Int,
        val removed: Int,
        val durationMs: Long,
    )

    /** 全部本地仓库（按创建顺序） */
    val localRepos: Flow<List<LocalRepoEntity>>

    /** 添加本地仓库；name 缺省时自动取 uri 尾段。返回新 id */
    suspend fun addLocalRepo(uriString: String, name: String?): Long

    suspend fun renameLocalRepo(id: Long, name: String)

    /** 开关：禁用后其书在书架隐藏且不参与刷新 */
    suspend fun setLocalRepoEnabled(id: Long, enabled: Boolean)

    /** 配置仓库：名称 / 封面文件名约定 / 默认添加分组（null = 不自动分组） */
    suspend fun updateLocalRepoConfig(id: Long, name: String, coverFileName: String, defaultGroupId: Long?)

    /** 删除仓库及其入库的书（含封面缓存）；授权的 SAF 权限无法主动撤销 */
    suspend fun removeLocalRepo(id: Long)

    /** 刷新单个仓库：重新扫描（新增）+ 对账（文件已消失的书移除，信息变化的更新） */
    suspend fun scanLocalRepo(id: Long): ScanReport

    // ---- 阅读配置 ----

    val readerPrefs: Flow<ReaderPrefs>

    suspend fun setReaderPrefs(prefs: ReaderPrefs)
}
