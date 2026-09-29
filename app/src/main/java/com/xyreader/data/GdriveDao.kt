package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.xyreader.core.GoogleDriveAccountEntity
import kotlinx.coroutines.flow.Flow

/** Google Drive 账号 DAO。SQL 列名与 core.GoogleDriveAccountEntity 字段一一对应。 */
@Dao
interface GdriveDao {

    /** 插入一条账号，返回自增 id */
    @Insert
    suspend fun insertAccount(account: GoogleDriveAccountEntity): Long

    /** 全部账号，按创建时间倒序 */
    @Query("SELECT * FROM gdrive_accounts ORDER BY createdAt DESC")
    fun observeAccounts(): Flow<List<GoogleDriveAccountEntity>>

    /** 按 id 查单条账号（archive 层打开远程书 / token 续期时解析凭据用） */
    @Query("SELECT * FROM gdrive_accounts WHERE id = :id")
    suspend fun getAccountById(id: Long): GoogleDriveAccountEntity?

    /** 按 id 删除一条账号 */
    @Query("DELETE FROM gdrive_accounts WHERE id = :id")
    suspend fun deleteAccountById(id: Long)
}
