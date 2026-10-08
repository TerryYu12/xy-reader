package com.xyreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阅读计时器：只在前台且 2 分钟内有交互时计时，落库取走后清零。 */
class ReadingTimeTrackerTest {

    private var now = 0L
    private val tracker = ReadingTimeTracker({ now })

    private fun advance(ms: Long) {
        now += ms
    }

    private fun sec(n: Long) = n * 1_000L

    private fun min(n: Long) = n * 60_000L

    @Test
    fun countsForegroundTimeWithRecentInteraction() {
        tracker.onResume()
        advance(sec(30))
        tracker.onInteraction()
        advance(sec(30))
        tracker.onPause()
        assertEquals(sec(60), tracker.drain())
    }

    @Test
    fun stopsCountingTwoMinutesAfterLastInteraction() {
        tracker.onResume()
        advance(min(10)) // 一直不碰屏幕
        assertEquals(min(2), tracker.drain())
        // 空闲期间再怎么等也不增加
        advance(min(5))
        assertEquals(0L, tracker.drain())
    }

    @Test
    fun interactionAfterIdleDoesNotBackfillTheGap() {
        tracker.onResume()
        advance(min(10))
        tracker.onInteraction() // 10 分钟后才碰屏幕：只算最初的 2 分钟
        advance(sec(20))
        assertEquals(min(2) + sec(20), tracker.drain())
    }

    @Test
    fun interactionKeepsExtendingTheWindow() {
        tracker.onResume()
        repeat(10) {
            advance(sec(90))
            tracker.onInteraction()
        }
        tracker.onPause()
        assertEquals(sec(900), tracker.drain())
    }

    @Test
    fun backgroundDoesNotCount() {
        tracker.onResume()
        advance(sec(20))
        tracker.onPause()
        advance(min(30))
        assertEquals(sec(20), tracker.drain())
    }

    @Test
    fun interactionsWhileInBackgroundAreIgnored() {
        tracker.onResume()
        advance(sec(10))
        tracker.onPause()
        advance(sec(5))
        tracker.onInteraction()
        advance(sec(50))
        tracker.onInteraction()
        assertEquals(sec(10), tracker.drain())
        // 回到前台后重新计
        tracker.onResume()
        advance(sec(15))
        assertEquals(sec(15), tracker.drain())
    }

    @Test
    fun drainSettlesFirstThenClears() {
        tracker.onResume()
        advance(sec(45))
        assertEquals(sec(45), tracker.drain())
        assertEquals(0L, tracker.drain())
        advance(sec(30))
        assertEquals(sec(30), tracker.drain())
        tracker.onPause()
        assertEquals(0L, tracker.drain())
    }

    @Test
    fun peekPendingDoesNotConsume() {
        tracker.onResume()
        advance(sec(40))
        assertEquals(sec(40), tracker.peekPending())
        assertEquals(sec(40), tracker.peekPending())
        assertEquals(sec(40), tracker.drain())
        assertEquals(0L, tracker.peekPending())
    }

    @Test
    fun restoreAddsBackFailedWrite() {
        tracker.onResume()
        advance(sec(40))
        val taken = tracker.drain()
        tracker.restore(taken)
        advance(sec(10))
        assertEquals(sec(50), tracker.drain())
        // 非正数的回放被忽略
        tracker.restore(0)
        tracker.restore(-5)
        assertEquals(0L, tracker.drain())
    }

    @Test
    fun repeatedResumeDoesNotDoubleCount() {
        tracker.onResume()
        advance(sec(10))
        tracker.onResume()
        advance(sec(10))
        tracker.onPause()
        tracker.onPause()
        assertEquals(sec(20), tracker.drain())
    }

    @Test
    fun rapidInteractionsAreThrottledButStillCounted() {
        tracker.onResume()
        repeat(100) {
            advance(100)
            tracker.onInteraction()
        }
        tracker.onPause()
        assertEquals(sec(10), tracker.drain())
    }
}
