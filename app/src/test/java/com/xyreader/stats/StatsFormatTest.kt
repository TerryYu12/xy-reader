package com.xyreader.stats

import org.junit.Assert.assertEquals
import org.junit.Test

/** 统计文案格式化：时长、字数、页数、阅读器「今日 N 分钟」。 */
class StatsFormatTest {

    @Test
    fun durationUnderAnHourShowsMinutesOnly() {
        assertEquals("0 分钟", formatDuration(0))
        assertEquals("0 分钟", formatDuration(59_999))
        assertEquals("1 分钟", formatDuration(60_000))
        assertEquals("59 分钟", formatDuration(59 * 60_000L + 59_999))
    }

    @Test
    fun durationOverAnHourShowsHoursAndMinutes() {
        assertEquals("1 小时 0 分钟", formatDuration(60 * 60_000L))
        assertEquals("2 小时 5 分钟", formatDuration(125 * 60_000L))
        assertEquals("100 小时 30 分钟", formatDuration(6030 * 60_000L))
        assertEquals("0 分钟", formatDuration(-5))
    }

    @Test
    fun charsUseWanAndYiUnits() {
        assertEquals("0 字", formatChars(0))
        assertEquals("9999 字", formatChars(9_999))
        assertEquals("1.0 万字", formatChars(10_000))
        assertEquals("12.3 万字", formatChars(123_456))
        // 向下截断，不会四舍五入成「10000.0 万字」
        assertEquals("9999.9 万字", formatChars(99_999_999))
        assertEquals("1.0 亿字", formatChars(100_000_000))
        assertEquals("2.3 亿字", formatChars(234_567_890))
        assertEquals("0 字", formatChars(-1))
    }

    @Test
    fun pagesText() {
        assertEquals("0 页", formatPages(0))
        assertEquals("1234 页", formatPages(1234))
    }

    @Test
    fun todayMinutesFloorsAndHandlesUnderOneMinute() {
        assertEquals("今日 <1 分钟", formatTodayMinutes(0))
        assertEquals("今日 <1 分钟", formatTodayMinutes(59_999))
        assertEquals("今日 1 分钟", formatTodayMinutes(60_000))
        assertEquals("今日 2 分钟", formatTodayMinutes(125_000))
        assertEquals("今日 <1 分钟", formatTodayMinutes(-10))
    }
}
