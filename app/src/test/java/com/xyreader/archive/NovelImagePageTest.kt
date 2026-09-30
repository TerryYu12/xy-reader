package com.xyreader.archive

import android.app.Application
import android.util.DisplayMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * EPUB 整页图片（封面页 / 插图页）分页与渲染测试：
 * 图片组按插入点穿插为整页图片；图片页「复制文字」为空；假图片字节解码失败不崩（留透明底）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelImagePageTest {

    private val metrics = DisplayMetrics().apply {
        widthPixels = 480
        heightPixels = 800
        density = 1f
        scaledDensity = 1f
        densityDpi = 160
    }

    private fun source(imageGroups: List<EpubImageGroup>): NovelPageSource = NovelPageSource(
        paragraphs = listOf(
            Paragraph("第一段的正文内容。", 0),
            Paragraph("第二段的正文内容。", 1),
        ),
        chapterMarks = listOf(ChapterMark("第一章", 0)),
        style = NovelStyle(0xff222222.toInt(), 19f, 480, 800),
        displayMetrics = metrics,
        imageGroups = imageGroups,
    )

    @Test
    fun imageGroupInsertedAsPageBeforeParagraphs() {
        val src = source(listOf(EpubImageGroup(0, listOf(byteArrayOf(1, 2, 3, 4)))))
        // 1 图片页 + 文本页
        assertTrue("页数应含图片页", src.pageCount >= 2)
        // 第 0 页是图片页：「复制文字」为空
        assertEquals("", runBlocking { src.pageText(0) })
        // 图片页渲染不崩（假字节解码失败 → 透明底）
        runBlocking { src.renderPage(0) }
        // 文本页正常
        assertTrue(runBlocking { src.pageText(1) }.contains("第一段"))
    }

    @Test
    fun multipleImagesBecomeMultiplePages() {
        val src = source(
            listOf(EpubImageGroup(0, listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))),
        )
        // 3 张图 = 3 个图片页（+ 文本页）
        assertTrue(src.pageCount >= 4)
        assertTrue(runBlocking { src.pageText(0) }.isEmpty())
        assertTrue(runBlocking { src.pageText(1) }.isEmpty())
        assertTrue(runBlocking { src.pageText(2) }.isEmpty())
    }

    @Test
    fun trailingImageGroupGoesToEnd() {
        val src = source(listOf(EpubImageGroup(2, listOf(byteArrayOf(9)))))
        // 图片组插在段落流末尾：最后一页是图片页
        val last = src.pageCount - 1
        assertEquals("", runBlocking { src.pageText(last) })
    }

    @Test
    fun noImageGroupsKeepsOldLayout() {
        val src = source(emptyList())
        // 无图：全部为文本页，首段可复制
        assertTrue(runBlocking { src.pageText(0) }.contains("第一段"))
    }
}
