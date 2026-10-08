package com.xyreader.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 按「到达新页」计数：起始页 / 跳转 / 回翻不计，单次前进最多 3 页才计。 */
class ReadingProgressCounterTest {

    /** 每页 100 字；第 5 页是图片页 0 字 */
    private val charsOfPage: (Int) -> Long = { page -> if (page == 5) 0L else 100L }

    private fun textCounter(start: Int = 0) = ReadingProgressCounter(start, textBook = true, charsOfPage = charsOfPage)

    private fun imageCounter(start: Int = 0) = ReadingProgressCounter(start, textBook = false, charsOfPage = { 999L })

    @Test
    fun startPageIsNotCounted() {
        val counter = textCounter(start = 10)
        assertTrue(counter.onPage(10).isEmpty)
    }

    @Test
    fun forwardOnePageCountsChars() {
        val counter = textCounter()
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(1))
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(2))
    }

    @Test
    fun forwardUpToThreePagesCountsEveryNewPage() {
        val counter = textCounter()
        assertEquals(ReadingProgressCounter.Gain(300, 0), counter.onPage(3))
    }

    @Test
    fun forwardMoreThanThreePagesIsAJumpAndNotCounted() {
        val counter = textCounter()
        assertTrue(counter.onPage(4).isEmpty)
        // 落点之后继续往前翻才计
        // 第 5 页是图片页（0 字），到达它也没有增量
        assertTrue(counter.onPage(5).isEmpty)
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(6))
    }

    @Test
    fun backwardMoveIsNotCountedAndRevisitDoesNotDoubleCount() {
        val counter = textCounter()
        counter.onPage(1)
        counter.onPage(2)
        assertTrue(counter.onPage(1).isEmpty)
        assertTrue(counter.onPage(0).isEmpty)
        // 重新读过已读页不再计
        assertTrue(counter.onPage(1).isEmpty)
        assertTrue(counter.onPage(2).isEmpty)
        // 到达没读过的页才计
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(3))
    }

    @Test
    fun samePageRepeatedIsNotCounted() {
        val counter = textCounter()
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(1))
        assertTrue(counter.onPage(1).isEmpty)
    }

    @Test
    fun jumpLandingPageIsMarkedSeen() {
        val counter = textCounter()
        assertTrue(counter.onPage(40).isEmpty) // 跳转
        assertTrue(counter.onPage(39).isEmpty) // 回翻
        // 40 是落点，已见过，不重复计；41 才是新页
        assertTrue(counter.onPage(40).isEmpty)
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(41))
    }

    @Test
    fun textBookOnlyAccumulatesCharsAndImageBookOnlyPages() {
        val text = textCounter()
        val textGain = text.onPage(2)
        assertEquals(200L, textGain.chars)
        assertEquals(0, textGain.pages)

        val image = imageCounter()
        val imageGain = image.onPage(2)
        assertEquals(0L, imageGain.chars)
        assertEquals(2, imageGain.pages)
    }

    @Test
    fun imagePageInTextBookContributesZeroChars() {
        val counter = textCounter(start = 4)
        assertTrue(counter.onPage(5).isEmpty) // 第 5 页是图片页，0 字
        assertEquals(ReadingProgressCounter.Gain(100, 0), counter.onPage(6))
    }

    @Test
    fun customMaxStepIsRespected() {
        val counter = ReadingProgressCounter(0, textBook = false, charsOfPage = { 0L }, maxStep = 1)
        assertTrue(counter.onPage(2).isEmpty)
        assertEquals(1, counter.onPage(3).pages)
    }
}
