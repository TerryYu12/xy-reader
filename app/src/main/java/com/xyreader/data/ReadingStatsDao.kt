package com.xyreader.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.xyreader.core.DailyReadingEntity
import kotlinx.coroutines.flow.Flow

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
}
