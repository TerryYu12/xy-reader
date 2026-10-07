package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.xyreader.core.BookEntity
import com.xyreader.core.BookmarkEntity
import kotlinx.coroutines.flow.Flow

/**
 * 书籍 + 书签 DAO（书签方法并入同一接口，数据库只暴露一个 DAO）。
 * 所有 SQL 列名与 core.BookEntity / core.BookmarkEntity 字段一一对应。
 */
@Dao
interface BookDao {

    // ---------- 书籍 ----------

    /** 插入一本书，返回自增 id */
    @Insert
    suspend fun insertBook(book: BookEntity): Long

    /** 全部书籍（默认按添加时间倒序），query 非空时按标题模糊过滤 */
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' ORDER BY addedAt DESC")
    fun observeBooks(query: String): Flow<List<BookEntity>>

    /** 仅收藏，默认按添加时间倒序 */
    @Query("SELECT * FROM books WHERE isFavorite = 1 AND title LIKE '%' || :query || '%' ORDER BY addedAt DESC")
    fun observeFavorites(query: String): Flow<List<BookEntity>>

    /** 阅读历史：lastReadAt 非空，按最近阅读倒序 */
    @Query("SELECT * FROM books WHERE lastReadAt IS NOT NULL AND title LIKE '%' || :query || '%' ORDER BY lastReadAt DESC")
    fun observeHistory(query: String): Flow<List<BookEntity>>

    /** 全部书籍按标题排序（COLLATE NOCASE 忽略大小写） */
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' ORDER BY title COLLATE NOCASE ASC")
    fun observeAllByTitle(query: String): Flow<List<BookEntity>>

    /** 全部书籍按添加时间倒序 */
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' ORDER BY addedAt DESC")
    fun observeAllByAdded(query: String): Flow<List<BookEntity>>

    /** 全部书籍按最近阅读倒序，未读过的（lastReadAt 为空）排最后 */
    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' ORDER BY lastReadAt IS NULL, lastReadAt DESC, addedAt DESC")
    fun observeAllByRecentRead(query: String): Flow<List<BookEntity>>

    /** 全部书籍，未读（currentPage == 0 且 totalPages > 0）优先，其余按添加时间倒序 */
    @Query(
        "SELECT * FROM books WHERE title LIKE '%' || :query || '%' " +
            "ORDER BY CASE WHEN currentPage = 0 AND totalPages > 0 THEN 0 ELSE 1 END, addedAt DESC",
    )
    fun observeAllByUnread(query: String): Flow<List<BookEntity>>

    /** 按 id 查单本 */
    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    /** 按 uri 查单本（外部导入去重用） */
    @Query("SELECT * FROM books WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): BookEntity?

    /** 取库里全部书籍 uri（扫描去重用） */
    @Query("SELECT uri FROM books")
    suspend fun getAllUris(): List<String>

    /** 整体更新一本书 */
    @Update
    suspend fun updateBook(book: BookEntity)

    /** 保存阅读进度并刷新最近阅读时间 */
    @Query("UPDATE books SET currentPage = :page, totalPages = :total, lastReadAt = :readAt WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, page: Int, total: Int, readAt: Long)

    /** 设置/取消收藏 */
    @Query("UPDATE books SET isFavorite = :fav WHERE id = :bookId")
    suspend fun setFavorite(bookId: Long, fav: Boolean)

    /** 按 id 删除一本书 */
    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteById(bookId: Long)

    // ---------- 批量（多选）----------
    // 批量方法的 id 个数受 SQLite 绑定变量上限约束，调用方需先分批（见 LibraryRepositoryImpl）。

    /** 按 id 批量查书（批量删除前收集封面路径用） */
    @Query("SELECT * FROM books WHERE id IN (:bookIds)")
    suspend fun getByIds(bookIds: List<Long>): List<BookEntity>

    /** 批量设置/取消收藏 */
    @Query("UPDATE books SET isFavorite = :fav WHERE id IN (:bookIds)")
    suspend fun setFavoriteForIds(bookIds: List<Long>, fav: Boolean)

    /** 批量清除阅读记录：进度归零、最近阅读时间置空（书、页数与书签保留） */
    @Query("UPDATE books SET currentPage = 0, lastReadAt = NULL WHERE id IN (:bookIds)")
    suspend fun clearProgressForIds(bookIds: List<Long>)

    /** 按 id 批量删除书籍 */
    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteByIds(bookIds: List<Long>)

    // ---------- 书签 ----------

    /** 插入书签，返回自增 id */
    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity): Long

    /** 按 id 删除书签 */
    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    /** 删除一本书时同时清理其所有书签 */
    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksByBookId(bookId: Long)

    /** 批量删除书籍时同时清理这些书的所有书签 */
    @Query("DELETE FROM bookmarks WHERE bookId IN (:bookIds)")
    suspend fun deleteBookmarksByBookIds(bookIds: List<Long>)

    /** 全部书签，按创建时间倒序 */
    @Query("SELECT * FROM bookmarks ORDER BY createdAt DESC")
    fun observeBookmarks(): Flow<List<BookmarkEntity>>
}
