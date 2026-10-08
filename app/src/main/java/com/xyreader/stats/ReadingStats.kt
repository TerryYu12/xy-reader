package com.xyreader.stats

import com.xyreader.core.DailyReadingEntity
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 当天累计阅读满该时长（5 分钟）即自动打卡 */
const val CHECK_IN_THRESHOLD_MS = 5 * 60_000L

/**
 * 连续打卡档位：按「历史最长连续天数」解锁（断签不收回），
 * 每档同时对应一枚徽章和一款封面边框（铜 / 银 / 金 / 紫晶 / 彩虹）。
 */
enum class StreakTier(
    /** 解锁所需的连续打卡天数 */
    val days: Int,
    val badgeTitle: String,
    val frameTitle: String,
) {
    BRONZE(3, "铜徽章", "铜边框"),
    SILVER(7, "银徽章", "银边框"),
    GOLD(30, "金徽章", "金边框"),
    AMETHYST(100, "紫晶徽章", "紫晶边框"),
    RAINBOW(365, "彩虹徽章", "彩虹边框"),
}

/** 统计页展示的汇总结果（纯数据，无 Android 依赖） */
data class ReadingStats(
    val totalDurationMs: Long,
    val totalChars: Long,
    val totalPages: Long,
    /** 有阅读时长的天数 */
    val readingDays: Int,
    /** 总时长 / 阅读天数；没有阅读天数时为 0 */
    val averageDailyMs: Long,
    val checkInDays: Int,
    /** 当前连续打卡天数：今天已打卡从今天起算，否则从昨天起算，都没有则 0 */
    val currentStreak: Int,
    val longestStreak: Int,
    val todayDurationMs: Long,
    val todayCheckedIn: Boolean,
    val checkInDates: Set<LocalDate>,
    /** 已解锁档位（days ≤ longestStreak），按档位升序 */
    val unlockedTiers: List<StreakTier>,
) {
    companion object {
        val EMPTY = ReadingStats(
            totalDurationMs = 0,
            totalChars = 0,
            totalPages = 0,
            readingDays = 0,
            averageDailyMs = 0,
            checkInDays = 0,
            currentStreak = 0,
            longestStreak = 0,
            todayDurationMs = 0,
            todayCheckedIn = false,
            checkInDates = emptySet(),
            unlockedTiers = emptyList(),
        )
    }
}

/** 最近 N 天格子里的一天 */
data class DayCell(
    val date: LocalDate,
    val durationMs: Long,
    val checkedIn: Boolean,
)

/** 把 yyyy-MM-dd 解析为日期；格式非法返回 null（脏数据直接跳过，不影响其他天） */
internal fun parseRecordDate(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

/** 同一天出现多行（正常不会，主键唯一）时把时长等累加合并，非法日期丢弃 */
private fun mergeByDate(records: List<DailyReadingEntity>): Map<LocalDate, DailyReadingEntity> {
    val merged = HashMap<LocalDate, DailyReadingEntity>()
    for (record in records) {
        val date = parseRecordDate(record.date) ?: continue
        val old = merged[date]
        merged[date] = if (old == null) {
            record
        } else {
            old.copy(
                durationMs = old.durationMs + record.durationMs,
                charsRead = old.charsRead + record.charsRead,
                pagesRead = old.pagesRead + record.pagesRead,
            )
        }
    }
    return merged
}

/** 由全部每日记录算出统计页所需数据；[today] 由调用方传入（便于测试与跨天刷新） */
fun computeReadingStats(records: List<DailyReadingEntity>, today: LocalDate): ReadingStats {
    val byDate = mergeByDate(records)
    if (byDate.isEmpty()) return ReadingStats.EMPTY

    var totalDuration = 0L
    var totalChars = 0L
    var totalPages = 0L
    var readingDays = 0
    for (row in byDate.values) {
        totalDuration += row.durationMs
        totalChars += row.charsRead
        totalPages += row.pagesRead
        if (row.durationMs > 0) readingDays++
    }

    val checkInEpochDays = byDate.entries
        .filter { it.value.durationMs >= CHECK_IN_THRESHOLD_MS }
        .map { it.key.toEpochDay() }
        .sorted()
    val checkInSet = checkInEpochDays.toHashSet()

    // 最长连续：升序扫描，相邻差 1 则延长，否则重新起算（跨月跨年按 epochDay 天然正确）
    var longest = 0
    var run = 0
    var previous = Long.MIN_VALUE
    for (day in checkInEpochDays) {
        run = if (previous != Long.MIN_VALUE && day == previous + 1) run + 1 else 1
        if (run > longest) longest = run
        previous = day
    }

    val todayEpoch = today.toEpochDay()
    val todayDuration = byDate[today]?.durationMs ?: 0L
    val todayCheckedIn = todayEpoch in checkInSet
    var cursor = if (todayCheckedIn) todayEpoch else todayEpoch - 1
    var current = 0
    while (cursor in checkInSet) {
        current++
        cursor--
    }

    return ReadingStats(
        totalDurationMs = totalDuration,
        totalChars = totalChars,
        totalPages = totalPages,
        readingDays = readingDays,
        averageDailyMs = if (readingDays > 0) totalDuration / readingDays else 0L,
        checkInDays = checkInEpochDays.size,
        currentStreak = current,
        longestStreak = longest,
        todayDurationMs = todayDuration,
        todayCheckedIn = todayCheckedIn,
        checkInDates = checkInEpochDays.map { LocalDate.ofEpochDay(it) }.toSet(),
        unlockedTiers = unlockedTiers(longest),
    )
}

/** 历史最长连续 [longestStreak] 天已解锁的档位（断签不收回） */
fun unlockedTiers(longestStreak: Int): List<StreakTier> =
    StreakTier.entries.filter { it.days <= longestStreak }

/** 距离解锁 [tier] 还差几天（已解锁为 0） */
fun daysToUnlock(tier: StreakTier, longestStreak: Int): Int =
    (tier.days - longestStreak).coerceAtLeast(0)

/** 最近 [n] 天（含今天，按日期升序）每天的时长与打卡状态；没有记录的天时长为 0 */
fun recentDays(records: List<DailyReadingEntity>, today: LocalDate, n: Int = 30): List<DayCell> {
    val byDate = mergeByDate(records)
    return (n - 1 downTo 0).map { offset ->
        val date = today.minusDays(offset.toLong())
        val duration = byDate[date]?.durationMs ?: 0L
        DayCell(date = date, durationMs = duration, checkedIn = duration >= CHECK_IN_THRESHOLD_MS)
    }
}
