package com.xyreader.stats

import com.xyreader.core.DailyReadingEntity
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 统计纯函数：时长 / 天数 / 平均、5 分钟打卡阈值、连续天数与徽章解锁。 */
class ReadingStatsTest {

    private val today = LocalDate.of(2026, 10, 8)

    private fun rec(date: LocalDate, ms: Long, chars: Long = 0, pages: Int = 0) =
        DailyReadingEntity(date.toString(), ms, chars, pages)

    /** 从 [start] 起连续 [days] 天，每天读满阈值 */
    private fun streak(start: LocalDate, days: Int) =
        (0 until days).map { rec(start.plusDays(it.toLong()), CHECK_IN_THRESHOLD_MS) }

    @Test
    fun emptyDataGivesZeros() {
        val stats = computeReadingStats(emptyList(), today)
        assertEquals(ReadingStats.EMPTY, stats)
        assertEquals(0, stats.currentStreak)
        assertEquals(0, stats.longestStreak)
        assertFalse(stats.todayCheckedIn)
        assertTrue(stats.unlockedTiers.isEmpty())
    }

    @Test
    fun totalsReadingDaysAndAverage() {
        val records = listOf(
            rec(today.minusDays(2), 10 * 60_000L, chars = 1000, pages = 0),
            rec(today.minusDays(1), 20 * 60_000L, chars = 2500, pages = 3),
            rec(today, 0L), // 占位行但没读，不算阅读天数
        )
        val stats = computeReadingStats(records, today)
        assertEquals(30 * 60_000L, stats.totalDurationMs)
        assertEquals(3500L, stats.totalChars)
        assertEquals(3L, stats.totalPages)
        assertEquals(2, stats.readingDays)
        assertEquals(15 * 60_000L, stats.averageDailyMs)
        assertEquals(2, stats.checkInDays)
        assertEquals(0L, stats.todayDurationMs)
    }

    @Test
    fun checkInThresholdBoundary() {
        val justUnder = computeReadingStats(listOf(rec(today, CHECK_IN_THRESHOLD_MS - 1)), today)
        assertFalse(justUnder.todayCheckedIn)
        assertEquals(0, justUnder.checkInDays)
        assertEquals(0, justUnder.currentStreak)

        val exactly = computeReadingStats(listOf(rec(today, CHECK_IN_THRESHOLD_MS)), today)
        assertTrue(exactly.todayCheckedIn)
        assertEquals(1, exactly.checkInDays)
        assertEquals(1, exactly.currentStreak)
        assertEquals(1, exactly.longestStreak)
        assertEquals(CHECK_IN_THRESHOLD_MS, exactly.todayDurationMs)
    }

    @Test
    fun currentStreakCountsFromYesterdayWhenTodayNotYetCheckedIn() {
        val records = streak(today.minusDays(3), 3) + rec(today, 60_000L)
        val stats = computeReadingStats(records, today)
        assertFalse(stats.todayCheckedIn)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.longestStreak)
    }

    @Test
    fun currentStreakIncludesTodayOnceCheckedIn() {
        val stats = computeReadingStats(streak(today.minusDays(3), 4), today)
        assertTrue(stats.todayCheckedIn)
        assertEquals(4, stats.currentStreak)
    }

    @Test
    fun brokenStreakResetsCurrentButKeepsLongest() {
        // 连续 5 天后断了两天，近期只有昨天打卡
        val records = streak(today.minusDays(9), 5) + rec(today.minusDays(1), CHECK_IN_THRESHOLD_MS)
        val stats = computeReadingStats(records, today)
        assertEquals(1, stats.currentStreak)
        assertEquals(5, stats.longestStreak)

        val longAgo = computeReadingStats(streak(today.minusDays(20), 5), today)
        assertEquals(0, longAgo.currentStreak)
        assertEquals(5, longAgo.longestStreak)
        // 断签不收回已解锁的档位
        assertEquals(listOf(StreakTier.BRONZE), longAgo.unlockedTiers)
    }

    @Test
    fun streakCrossesMonthAndYearBoundaries() {
        val newYear = LocalDate.of(2027, 1, 2)
        val stats = computeReadingStats(streak(LocalDate.of(2026, 12, 29), 5), newYear)
        assertEquals(5, stats.longestStreak)
        assertEquals(5, stats.currentStreak)

        // 闰年 2 月 28 → 3 月 1（2028 是闰年，中间有 2 月 29）
        val leap = computeReadingStats(streak(LocalDate.of(2028, 2, 28), 3), LocalDate.of(2028, 3, 1))
        assertEquals(3, leap.longestStreak)
        assertEquals(3, leap.currentStreak)
    }

    @Test
    fun tiersUnlockByLongestStreak() {
        fun tiersFor(days: Int) = computeReadingStats(streak(today.minusDays(days.toLong() - 1), days), today)
            .unlockedTiers

        assertEquals(emptyList<StreakTier>(), tiersFor(2))
        assertEquals(listOf(StreakTier.BRONZE), tiersFor(3))
        assertEquals(listOf(StreakTier.BRONZE, StreakTier.SILVER), tiersFor(7))
        assertEquals(StreakTier.entries.take(3), tiersFor(30))
        assertEquals(StreakTier.entries.take(4), tiersFor(100))
        assertEquals(StreakTier.entries, tiersFor(365))
        assertEquals(listOf(3, 7, 30, 100, 365), StreakTier.entries.map { it.days })
    }

    @Test
    fun daysToUnlockCountsDownAndStopsAtZero() {
        assertEquals(3, daysToUnlock(StreakTier.BRONZE, 0))
        assertEquals(1, daysToUnlock(StreakTier.BRONZE, 2))
        assertEquals(0, daysToUnlock(StreakTier.BRONZE, 3))
        assertEquals(0, daysToUnlock(StreakTier.BRONZE, 50))
        assertEquals(315, daysToUnlock(StreakTier.RAINBOW, 50))
    }

    @Test
    fun invalidDateStringsAreIgnored() {
        val records = listOf(
            DailyReadingEntity("not-a-date", 99 * 60_000L),
            DailyReadingEntity("2026-13-45", 99 * 60_000L),
            rec(today, 10 * 60_000L),
        )
        val stats = computeReadingStats(records, today)
        assertEquals(10 * 60_000L, stats.totalDurationMs)
        assertEquals(1, stats.readingDays)
        assertEquals(1, stats.checkInDays)
    }

    @Test
    fun recentDaysCoversWindowEndingToday() {
        val records = listOf(
            rec(today, CHECK_IN_THRESHOLD_MS),
            rec(today.minusDays(1), 60_000L),
            rec(today.minusDays(29), CHECK_IN_THRESHOLD_MS),
            rec(today.minusDays(30), CHECK_IN_THRESHOLD_MS), // 窗口之外
        )
        val cells = recentDays(records, today, 30)
        assertEquals(30, cells.size)
        assertEquals(today.minusDays(29), cells.first().date)
        assertEquals(today, cells.last().date)
        assertTrue(cells.first().checkedIn)
        assertTrue(cells.last().checkedIn)
        val yesterday = cells[28]
        assertEquals(60_000L, yesterday.durationMs)
        assertFalse(yesterday.checkedIn)
        assertEquals(0L, cells[10].durationMs)
    }
}
