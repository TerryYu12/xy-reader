package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.xyreader.core.WebDavConfigEntity
import kotlinx.coroutines.flow.Flow

/** WebDAV 远程仓库配置表 DAO。SQL 列名与 core.WebDavConfigEntity 字段一一对应。 */
@Dao
interface WebDavDao {

    /** 插入一条配置，返回自增 id */
    @Insert
    suspend fun insertConfig(config: WebDavConfigEntity): Long

    /** 全部配置，按创建时间倒序 */
    @Query("SELECT * FROM webdav_configs ORDER BY createdAt DESC")
    fun observeConfigs(): Flow<List<WebDavConfigEntity>>

    /** 按 id 查单条配置（archive 层打开远程书时解析凭据用） */
    @Query("SELECT * FROM webdav_configs WHERE id = :id")
    suspend fun getConfigById(id: Long): WebDavConfigEntity?

    /** 按 id 删除一条配置 */
    @Query("DELETE FROM webdav_configs WHERE id = :id")
    suspend fun deleteConfigById(id: Long)
}
