package com.xyreader.data

import android.content.Context
import android.util.Log
import com.xyreader.core.BookEntity
import com.xyreader.core.BookFormat
import com.xyreader.core.GoogleDriveAccountEntity
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Google Drive 远程仓库扫描器：从账号目标文件夹起递归 files.list，
 * 把远端压缩包/PDF/EPUB 文件按 "gdrive://{accountId}/{fileId}" 约定入库，
 * 并逐本补页数与封面。
 * 一期不做远程图片目录书：目录只递归，不作为书入库。
 */
class GoogleDriveScanner(private val dao: BookDao) {

    /**
     * 扫描一个账号的目标目录树，新书籍入库。
     * folderId 为空串时扫整个 My Drive 根目录（Drive API 的 'root' 别名）。
     * @return 本次新增的书籍数量
     */
    suspend fun scan(account: GoogleDriveAccountEntity, context: Context): Int = withContext(Dispatchers.IO) {
        // 扫描开始时用 refresh_token 换一次 access token（有效期约 1 小时，足够一次扫描）。
        // 与仓库层 TokenManager 的缓存互不影响：Google 允许同一 refresh_token 并存多个有效 access token。
        // 授权失效（401/invalid_grant）时快速失败，让上层提示重新授权，而不是空扫一场
        val tokenInfo = try {
            GoogleDriveClient.refreshToken(account.clientId, account.clientSecret, account.refreshToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IOException("获取访问令牌失败，无法扫描: ${e.message}", e)
        }
        val token = tokenInfo.accessToken

        // 库里已有 uri，用于按 uri 去重（本次扫描新加的也会同步加进来）
        val existingUris = dao.getAllUris().toHashSet()
        // 已访问目录集合，防止共享/快捷方式造成的服务器端环
        val visitedFolders = HashSet<String>()
        // 本次新插入的书（已带数据库自增 id）
        val inserted = mutableListOf<BookEntity>()
        // 空串表示整个 My Drive 根目录
        val rootFolderId = account.folderId.trim().ifEmpty { "root" }

        suspend fun walk(folderId: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            // 防环：同一目录只访问一次
            if (!visitedFolders.add(folderId)) return
            val children = try {
                GoogleDriveClient.listChildren(token, folderId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 注意：异常消息只含状态码/响应摘要，不含任何凭据；单个目录失败不阻断整体扫描
                Log.w(TAG, "files.list 失败，跳过目录 folder=$folderId: ${e.message}")
                return
            }
            for (entry in children) {
                when {
                    entry.isFolder -> walk(entry.id, depth + 1)
                    // 一期只入库压缩包/PDF/EPUB 等单文件书
                    BookFormat.isSupportedFile(entry.name) ->
                        insertFileBook(account.id, entry, existingUris, inserted)
                    // 其余条目（图片/文档等）忽略
                }
            }
        }

        walk(rootFolderId, 0)

        // 扫描插入完成后逐本补页数与封面。gdrive:// 远程打开可能整包下载（rar/pdf），
        // 必须有超时保护；失败/超时不阻断入库，totalPages 留 0，阅读时 ensureCover 机制会补
        val coverScanner = LibraryScanner(context.applicationContext, dao)
        for (book in inserted) {
            try {
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

    /** 单个文件入库（uri 按 "gdrive://{accountId}/{fileId}" 约定构造，按 uri 去重） */
    private suspend fun insertFileBook(
        accountId: Long,
        entry: GDriveEntry,
        existingUris: MutableSet<String>,
        inserted: MutableList<BookEntity>,
    ) {
        val uri = buildGdriveUri(accountId, entry.id)
        // 按 uri 去重：库里已有或本次已加过则跳过
        if (!existingUris.add(uri)) return
        val entity = BookEntity(
            title = entry.name.substringBeforeLast('.'),
            uri = uri,
            format = BookFormat.fromFileName(entry.name).name,
            size = entry.size,
            addedAt = System.currentTimeMillis(),
        )
        val id = dao.insertBook(entity)
        if (id > 0) inserted += entity.copy(id = id)
    }

    companion object {
        private const val TAG = "GoogleDriveScanner"

        /** 根为第 0 层，最深进入第 8 层子目录，与本地/WebDAV 扫描一致，防异常深的树拖死扫描 */
        private const val MAX_DEPTH = 8

        /** 单本封面/页数获取的超时保护（rar/pdf 远程打开会整包下载） */
        private const val COVER_TIMEOUT_MS = 60_000L

        /** 远程书统一 uri：gdrive://{accountId}/{fileId}；fileId 为 [A-Za-z0-9_-]，不做编码 */
        fun buildGdriveUri(accountId: Long, fileId: String): String = "gdrive://$accountId/$fileId"
    }
}
