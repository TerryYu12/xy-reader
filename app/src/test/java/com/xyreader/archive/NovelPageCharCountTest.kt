package com.xyreader.archive

import android.app.Application
import android.util.DisplayMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * NovelPageSource.pageCharCount：各文本页字数之和必须等于「全书文本总长 − 首页起点」，
 * 即不重复、不遗漏；整页图片页与越界页码为 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelPageCharCountTest {

    private val metrics = DisplayMetrics().apply {
        widthPixels = 480
        heightPixels = 800
        density = 1f
        scaledDensity = 1f
        densityDpi = 160
    }

    private val paragraphs = List(50) { i ->
        Paragraph("第${i}段：中文排版测试。字体变大以后，一页能容纳的字数应当减少。".repeat(8), i / 10)
    }

    /** 归一化全书文本长度：段落之间以一个换行符分隔 */
    private val totalLength: Long = paragraphs.sumOf { it.text.length + 1L } - 1L

    private fun source(
        chapterNewPage: Boolean = false,
        imageGroups: List<EpubImageGroup> = emptyList(),
    ): NovelPageSource = NovelPageSource(
        paragraphs = paragraphs,
        chapterMarks = List(5) { ChapterMark("第${it + 1}章", it * 10) },
        style = NovelStyle(0xff222222.toInt(), 19f, 480, 800, chapterNewPage = chapterNewPage),
        displayMetrics = metrics,
        imageGroups = imageGroups,
    )

    @Test
    fun pageCharCountsSumToWholeText() {
        val src = source()
        try {
            assertTrue("应分成多页", src.pageCount > 3)
            val counts = (0 until src.pageCount).map { src.pageCharCount(it) }
            assertTrue("每个文本页都应有字数", counts.all { it > 0 })
            assertEquals(totalLength - src.pageStartCharOffset(0), counts.sum())
        } finally {
            src.close()
        }
    }

    @Test
    fun sumStaysConsistentWithChapterNewPage() {
        val src = source(chapterNewPage = true)
        try {
            val counts = (0 until src.pageCount).map { src.pageCharCount(it) }
            assertTrue(counts.all { it > 0 })
            assertEquals(totalLength - src.pageStartCharOffset(0), counts.sum())
        } finally {
            src.close()
        }
    }

    @Test
    fun imagePagesCountZeroAndTextPagesStillSumToWholeText() {
        val src = source(imageGroups = listOf(EpubImageGroup(0, listOf(byteArrayOf(1, 2, 3, 4)))))
        try {
            // 第 0 页是插入在开头的整页图片
            assertEquals(0L, src.pageCharCount(0))
            val counts = (0 until src.pageCount).map { src.pageCharCount(it) }
            assertEquals(totalLength, counts.sum())
        } finally {
            src.close()
        }
    }

    @Test
    fun outOfRangePagesCountZero() {
        val src = source()
        try {
            assertEquals(0L, src.pageCharCount(-1))
            assertEquals(0L, src.pageCharCount(src.pageCount))
        } finally {
            src.close()
        }
    }
}
