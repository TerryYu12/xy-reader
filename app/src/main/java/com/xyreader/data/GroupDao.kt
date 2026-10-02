package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书架分组 DAO（分组表 + 分组内书籍的查询/移动）。
 * 全部 SQL 列名与 core.BookGroupEntity / core.BookEntity 字段一一对应。
 * 分组相关操作集中放在本文件，避免改动共享的 BookDao。
 */
@Dao
interface GroupDao {

    // ---------- 分组表 ----------

    /** 插入一个分组，返回自增 id */
    @Insert
    suspend fun insertGroup(group: BookGroupEntity): Long

    /** 全部分组，按创建时间升序（保持创建顺序） */
    @Query("SELECT * FROM book_groups ORDER BY createdAt ASC")
    fun observeGroups(): Flow<List<BookGroupEntity>>

    /** 按 id 查单个分组 */
    @Query("SELECT * FROM book_groups WHERE id = :id")
    suspend fun getGroupById(id: Long): BookGroupEntity?

    /** 重命名分组 */
    @Query("UPDATE book_groups SET name = :name WHERE id = :id")
    suspend fun updateGroupName(id: Long, name: String)

    /** 设置或清除分组自定义封面路径 */
    @Query("UPDATE book_groups SET coverPath = :coverPath WHERE id = :id")
    suspend fun updateGroupCover(id: Long, coverPath: String?): Int

    /** 按 id 删除分组（组内书籍需先由调用方清空归属） */
    @Query("DELETE FROM book_groups WHERE id = :id")
    suspend fun deleteGroupById(id: Long)

    // ---------- 分组内书籍 ----------

    /** 未分组的书（groupId 为空），按添加时间倒序；其余排序由仓库层在内存补排 */
    @Query("SELECT * FROM books WHERE groupId IS NULL ORDER BY addedAt DESC")
    fun observeUngroupedBooks(): Flow<List<BookEntity>>

    /** 某个分组内的书，按添加时间倒序；其余排序由仓库层在内存补排 */
    @Query("SELECT * FROM books WHERE groupId = :groupId ORDER BY addedAt DESC")
    fun observeGroupBooks(groupId: Long): Flow<List<BookEntity>>

    /** 把书移入分组；groupId 传 null 表示移出分组（Room 会绑定为 NULL） */
    @Query("UPDATE books SET groupId = :groupId WHERE id = :bookId")
    suspend fun moveBookToGroup(bookId: Long, groupId: Long?)

    /** 删除分组前把该组所有书的 groupId 置空（书自动回到未分组） */
    @Query("UPDATE books SET groupId = NULL WHERE groupId = :id")
    suspend fun clearBooksOfGroup(id: Long)
}
