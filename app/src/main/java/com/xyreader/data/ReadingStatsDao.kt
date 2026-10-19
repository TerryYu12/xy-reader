package com.xyreader.data

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.xyreader.core.BookReadingEntity
import com.xyreader.core.DailyReadingEntity
import kotlinx.coroutines.flow.Flow

/** 排行榜数据行：书籍稳定 ID + 书名 + 累计时长（由 book_reading 与 books 连接取得） */
data class BookDurationRow(
    @ColumnInfo(name = "bookId") val bookId: Long,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "durationMs") val durationMs: Long,
)

/**
 * 每日阅读统计 DAO。
 *
 * 按天累加不用 `INSERT ... ON CONFLICT DO UPDATE`（UPSERT 需要 SQLite 3.24，
 * Android 8.0~10 自带版本更低，运行时会崩溃，而 minSdk 为 26）：
 * 先用 IGNORE 占位一行零值，再 `UPDATE col = col + :delta`，两步放在同一事务里。
 * 设计为 abstract class 以便用 [Transaction] 组合两步（接口 DAO 的默认方法在 Room 2.6 下不可靠）。
 */
@Dao
abstract class ReadingStatsDao {

    /** 当天还没有记录时插入零值占位行；已存在则忽略，返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(row: DailyReadingEntity): Long

    /** 在已有行上累加增量 */
    @Query(
        "UPDATE daily_reading SET durationMs = durationMs + :durationMs, " +
            "charsRead = charsRead + :chars, pagesRead = pagesRead + :pages WHERE date = :date",
    )
    abstract suspend fun addToRow(date: String, durationMs: Long, chars: Long, pages: Int)

    /** 把一段阅读增量累加到 [date]（yyyy-MM-dd）那一天；占位与累加在同一事务内 */
    @Transaction
    open suspend fun addDelta(date: String, durationMs: Long, chars: Long, pages: Int) {
        insertIfAbsent(DailyReadingEntity(date = date))
        addToRow(date, durationMs, chars, pages)
    }

    /** 某天的记录；没有读过为 null */
    @Query("SELECT * FROM daily_reading WHERE date = :date")
    abstract suspend fun get(date: String): DailyReadingEntity?

    /** 某天的记录流（阅读器「今日 N 分钟」） */
    @Query("SELECT * FROM daily_reading WHERE date = :date")
    abstract fun observe(date: String): Flow<DailyReadingEntity?>

    /** 全部记录，按日期升序（字典序即时间序） */
    @Query("SELECT * FROM daily_reading ORDER BY date ASC")
    abstract fun observeAll(): Flow<List<DailyReadingEntity>>

    /** 全部记录，按日期升序 */
    @Query("SELECT * FROM daily_reading ORDER BY date ASC")
    abstract suspend fun getAll(): List<DailyReadingEntity>

    /** 删除某天的记录（测试清理用） */
    @Query("DELETE FROM daily_reading WHERE date = :date")
    abstract suspend fun deleteByDate(date: String)

    // ---------- 按书累计时长（book_reading） ----------

    /** 这本书还没有记录时插入零值占位行；已存在则忽略，返回 -1 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertBookIfAbsent(row: BookReadingEntity): Long

    /** 在已有的分书记录上累加时长 */
    @Query("UPDATE book_reading SET durationMs = durationMs + :durationMs, lastReadAt = :lastReadAt WHERE bookId = :bookId")
    abstract suspend fun addToBookRow(bookId: Long, durationMs: Long, lastReadAt: Long)

    /** 把一段时长累加到 [bookId] 这一本；占位与累加同一事务 */
    @Transaction
    open suspend fun addBookDelta(bookId: Long, durationMs: Long, lastReadAt: Long) {
        if (durationMs <= 0L) return
        insertBookIfAbsent(BookReadingEntity(bookId = bookId))
        addToBookRow(bookId, durationMs, lastReadAt)
    }

    /**
     * 一次落库：当日累计（daily_reading）与分书累计（book_reading）写在同一事务里，
     * 两表要么都成功、要么都不写，避免调用方重试时把其中一边算两遍。
     * [durationMs] 为 0（只读了字数 / 页数）时不建分书记录。
     */
    @Transaction
    open suspend fun addReadingDelta(
        date: String,
        durationMs: Long,
        chars: Long,
        pages: Int,
        bookId: Long,
        lastReadAt: Long,
    ) {
        insertIfAbsent(DailyReadingEntity(date = date))
        addToRow(date, durationMs, chars, pages)
        if (durationMs > 0L) {
            insertBookIfAbsent(BookReadingEntity(bookId = bookId))
            addToBookRow(bookId, durationMs, lastReadAt)
        }
    }

    /** 这本书的累计时长；没有计时记录为 null（与「真实 0 分钟」区分） */
    @Query("SELECT * FROM book_reading WHERE bookId = :bookId")
    abstract suspend fun getBookDuration(bookId: Long): BookReadingEntity?

    /**
     * 排行榜数据：只包含有计时记录的书籍，按时长降序。
     * 用 INNER JOIN：书籍从书库删除后其分书记录不再出现在排行榜上，
     * 但 book_reading 里的行不清空（重新导入同一 ID 不会发生，不影响口径）。
     */
    @Query(
        "SELECT books.id AS bookId, books.title AS title, book_reading.durationMs AS durationMs " +
            "FROM book_reading INNER JOIN books ON books.id = book_reading.bookId " +
            "ORDER BY book_reading.durationMs DESC, books.title ASC",
    )
    abstract fun observeBookDurations(): Flow<List<BookDurationRow>>

    @Query("DELETE FROM book_reading WHERE bookId = :bookId")
    abstract suspend fun deleteBookDuration(bookId: Long)
}
