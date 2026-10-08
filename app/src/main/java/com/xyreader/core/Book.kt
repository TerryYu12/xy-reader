package com.xyreader.core

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 一本漫画/书籍的库记录 */
@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** SAF 文档 tree/document URI，或 file:// 路径 */
    val uri: String,
    /** 所在父目录 URI（仓库内定位用，可为空） */
    val parentUri: String? = null,
    val isDirectory: Boolean = false,
    /** BookFormat.name */
    val format: String,
    val size: Long = 0,
    /** 封面缓存文件绝对路径，未生成时为 null */
    val coverPath: String? = null,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    /** 最近阅读时间 epoch ms，未读过为 null */
    val lastReadAt: Long? = null,
    val addedAt: Long,
    val isFavorite: Boolean = false,
    /** 所属书架分组 id；null = 未分组 */
    val groupId: Long? = null,
    /** 所属本地仓库 id（本地扫描入库时写入，用于按仓库刷新对账与启停过滤） */
    val localRepoId: Long? = null,
)

/** 本地仓库（Room 表，替代早期 DataStore 的 uri 列表）：支持命名/开关/封面约定/默认分组/PDF 合并 */
@Entity(tableName = "local_repos")
data class LocalRepoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** SAF 目录树 URI */
    val uri: String,
    /** 开关：禁用后不参与刷新，其书在书架隐藏 */
    val enabled: Boolean = true,
    /** 多章节漫画根目录封面文件名（不含扩展名，不区分大小写），空串 = 不启用 */
    val coverFileName: String = "",
    /** 新扫描到的书自动加入该分组；null = 不自动加入 */
    val defaultGroupId: Long? = null,
    val createdAt: Long,
    /** 同文件夹 PDF 合并：子文件夹直接包含 ≥2 个 PDF 时整个文件夹入库为一本「PDF 合集」（仓库根目录除外） */
    @ColumnInfo(defaultValue = "1")
    val mergeFolderPdfs: Boolean = true,
)

/** 自定义书架分组（一本书最多归属一个分组） */
@Entity(tableName = "book_groups")
data class BookGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** 应用私有目录中的自定义封面副本；未设置时为空 */
    val coverPath: String? = null,
)

/** 阅读器内添加的书签 */
@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val pageIndex: Int,
    val createdAt: Long,
)

/** WebDAV 远程仓库配置（坚果云/Alist 等） */
@Entity(tableName = "webdav_configs")
data class WebDavConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 形如 https://dav.jianguoyun.com/dav/ —— 建议以 / 结尾 */
    val baseUrl: String,
    val username: String,
    /** 坚果云等使用应用密码而非登录密码 */
    val password: String,
    val createdAt: Long,
)

/** Google Drive（Google One）账号：OAuth2 授权信息 */
@Entity(tableName = "gdrive_accounts")
data class GoogleDriveAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Google Cloud OAuth 客户端 ID（Desktop app 类型） */
    val clientId: String,
    /** OAuth 客户端密钥（Desktop 类型按 Google 规则不算机密，但不可公开分发） */
    val clientSecret: String,
    /** 刷新令牌；access token 不持久化，运行时按需换取 */
    val refreshToken: String,
    /** 扫描目标文件夹 ID；空串表示整个 My Drive 根目录 */
    val folderId: String,
    val createdAt: Long,
)

/**
 * 每日阅读统计（本机私有，不上传）：一天一行，按本地日期累加。
 * 主键 [date] 为 ISO 本地日期 yyyy-MM-dd，字典序即时间序。
 */
@Entity(tableName = "daily_reading")
data class DailyReadingEntity(
    @PrimaryKey val date: String,
    /** 当天累计阅读时长（毫秒） */
    val durationMs: Long = 0,
    /** 当天累计阅读字数（仅文字书） */
    val charsRead: Long = 0,
    /** 当天累计阅读页数（仅图片类书籍） */
    val pagesRead: Int = 0,
)
