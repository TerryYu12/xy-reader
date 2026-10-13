package com.xyreader.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 打卡成功检测：只有「本次阅读中亲眼看到越线」才触发，且每天最多一次。 */
class CheckInDetectorTest {

    private val day = LocalDate.of(2026, 10, 13)
    private val line = CHECK_IN_THRESHOLD_MS

    @Test
    fun crossingTheLineFiresOnce() {
        val detector = CheckInDetector()
        assertFalse(detector.observe(day, 0L))
        assertFalse(detector.observe(day, line - 10_000L))
        assertTrue(detector.observe(day, line + 2_000L))
        // 越线之后继续读不再触发
        assertFalse(detector.observe(day, line + 12_000L))
        assertFalse(detector.observe(day, line * 3))
    }

    @Test
    fun landingExactlyOnTheLineCounts() {
        val detector = CheckInDetector()
        detector.observe(day, line - 1L)
        assertTrue(detector.observe(day, line))
    }

    @Test
    fun alreadyCheckedInWhenOpeningDoesNotFire() {
        // 打开阅读器时今天早就读满了：第一次上报只记基线
        val detector = CheckInDetector()
        assertFalse(detector.observe(day, line + 1L))
        assertFalse(detector.observe(day, line + 10_000L))
    }

    @Test
    fun crossingInASingleJumpAfterBaselineStillFires() {
        // 两次上报之间时长一下跳过阈值（比如后台落库后补上来）也算越线
        val detector = CheckInDetector()
        detector.observe(day, 30_000L)
        assertTrue(detector.observe(day, line + 60_000L))
    }

    @Test
    fun newDayResetsTheBaseline() {
        val detector = CheckInDetector()
        detector.observe(day, 0L)
        assertTrue(detector.observe(day, line))

        // 跨零点继续读：新的一天第一次上报只记基线……
        val next = day.plusDays(1)
        assertFalse(detector.observe(next, 20_000L))
        // ……再次读满才又算一次打卡
        assertTrue(detector.observe(next, line))
    }

    @Test
    fun midnightRolloverWithLargeFirstValueDoesNotFire() {
        val detector = CheckInDetector()
        detector.observe(day, line + 5_000L)
        // 新一天第一次上报就已经满了（不现实，但不该误触发）
        assertFalse(detector.observe(day.plusDays(1), line))
    }

    @Test
    fun customThreshold() {
        val detector = CheckInDetector(thresholdMs = 1_000L)
        detector.observe(day, 0L)
        assertFalse(detector.observe(day, 999L))
        assertTrue(detector.observe(day, 1_000L))
    }

    @Test
    fun subtitleMentionsStreakOnlyFromTwoDays() {
        assertEquals("今日阅读已满 5 分钟", formatCheckInSubtitle(0))
        assertEquals("今日阅读已满 5 分钟", formatCheckInSubtitle(1))
        assertEquals("今日阅读已满 5 分钟 · 已连续打卡 2 天", formatCheckInSubtitle(2))
        assertEquals("今日阅读已满 5 分钟 · 已连续打卡 30 天", formatCheckInSubtitle(30))
        assertEquals("今日阅读已满 10 分钟", formatCheckInSubtitle(1, thresholdMs = 10 * 60_000L))
    }
}
