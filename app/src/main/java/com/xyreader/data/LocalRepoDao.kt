package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.xyreader.core.BookEntity
import com.xyreader.core.LocalRepoEntity
import kotlinx.coroutines.flow.Flow

/**
 * 本地仓库 DAO（local_repos 表 + 仓库内书籍的查询）。
 * 全部 SQL 列名与 core.LocalRepoEntity / core.BookEntity 字段一一对应。
 */
@Dao
interface LocalRepoDao {

    /** 插入一个仓库，返回自增 id */
    @Insert
    suspend fun insertRepo(repo: LocalRepoEntity): Long

    /** 全部仓库，按创建时间升序（保持创建顺序） */
    @Query("SELECT * FROM local_repos ORDER BY createdAt ASC")
    fun observeRepos(): Flow<List<LocalRepoEntity>>

    /** 按 id 查单个仓库 */
    @Query("SELECT * FROM local_repos WHERE id = :id")
    suspend fun getRepoById(id: Long): LocalRepoEntity?

    /** 按 uri 查仓库（旧 API 兼容与添加去重用） */
    @Query("SELECT * FROM local_repos WHERE uri = :uri")
    suspend fun getRepoByUri(uri: String): LocalRepoEntity?

    /** 重命名仓库 */
    @Query("UPDATE local_repos SET name = :name WHERE id = :id")
    suspend fun renameRepo(id: Long, name: String)

    /** 开关仓库：禁用后其书在书架隐藏且不参与刷新 */
    @Query("UPDATE local_repos SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    /** 配置仓库：名称 / 封面文件名约定 / 默认添加分组（null = 不自动分组） / 同文件夹 PDF 合并开关 */
    @Query(
        "UPDATE local_repos SET name = :name, coverFileName = :coverFileName, " +
            "defaultGroupId = :defaultGroupId, mergeFolderPdfs = :mergeFolderPdfs WHERE id = :id",
    )
    suspend fun updateConfig(
        id: Long,
        name: String,
        coverFileName: String,
        defaultGroupId: Long?,
        mergeFolderPdfs: Boolean,
    )

    /** 按 id 删除仓库（仓库内的书由调用方先按 getBooksOfRepo 列表逐本删除） */
    @Query("DELETE FROM local_repos WHERE id = :id")
    suspend fun deleteRepoById(id: Long)

    // ---------- 仓库内书籍 ----------

    /** 某仓库已入库的书（刷新对账的基准集合） */
    @Query("SELECT * FROM books WHERE localRepoId = :repoId")
    suspend fun getBooksOfRepo(repoId: Long): List<BookEntity>

    /** 全库书籍：扫描时用于把旧版入库但未绑定仓库的遗留书按 uri 收编，避免刷新后重复入库 */
    @Query("SELECT * FROM books")
    suspend fun getAllBooks(): List<BookEntity>
}
