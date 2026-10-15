package com.xyreader.archive

import android.app.Application
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Markdown 经 [NovelPageSource] 的分页 / 目录 / 渲染（真实 StaticLayout，不 mock 行高） */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownPageSourceTest {
    private val metrics = DisplayMetrics().apply {
        widthPixels = 480
        heightPixels = 800
        density = 1f
        scaledDensity = 1f
        densityDpi = 160
    }

    private val sample = buildString {
        append("# 书名\n\n")
        repeat(6) { c ->
            append("## 第${c + 1}节\n\n")
            append("正文段落，包含 **粗体**、*斜体* 与 `代码`。".repeat(12)).append("\n\n")
            append("- 列表项一\n- 列表项二\n\n")
            append("> 引用内容\n\n")
            append("```\ncode line 1\ncode line 2\n```\n\n")
            append("| 列一 | 列二 |\n|---|---|\n| a | b |\n| c | d |\n\n")
            append("---\n\n")
        }
    }

    private fun source(md: String, size: Float = 16f): NovelPageSource {
        val (paras, marks) = MarkdownParser.parse(md)
        return NovelPageSource(
            paragraphs = paras,
            chapterMarks = marks,
            style = NovelStyle(0xff222222.toInt(), size, 480, 800),
            displayMetrics = metrics,
        )
    }

    @Test fun chaptersFollowHeadingsAndPagesAreMonotonic() {
        val src = source(sample)
        try {
            assertEquals(7, src.chapters.size)
            assertEquals("书名", src.chapters.first().title)
            assertTrue(src.chapters.zipWithNext().all { (a, b) -> a.startPage <= b.startPage })
            assertTrue(src.pageCount > 1)
        } finally {
            src.close()
        }
    }

    @Test fun largerFontNeedsMorePages() {
        val small = source(sample, 14f)
        val large = source(sample, 28f)
        try {
            assertTrue(large.pageCount > small.pageCount)
        } finally {
            small.close()
            large.close()
        }
    }

    @Test fun everyPageRendersAndCopyTextHasNoMarkup() = runBlocking {
        val src = source(sample)
        try {
            for (page in 0 until src.pageCount) {
                val bitmap = src.renderPage(page).asAndroidBitmap()
                assertEquals(480, bitmap.width)
                assertEquals(800, bitmap.height)
            }
            val all = (0 until src.pageCount).map { src.pageText(it) }.joinToString("\n")
            assertTrue(all.contains("粗体"))
            assertFalse("复制出的文字不应带 Markdown 标记", all.contains("**") || all.contains("```") || all.contains("|---"))
        } finally {
            src.close()
        }
    }

    @Test fun headingMakesPageContentDifferFromPlainText() = runBlocking {
        val md = source("# 大标题\n\n正文一行")
        val plain = source("大标题\n正文一行")
        try {
            val a = md.renderPage(0).asAndroidBitmap()
            val b = plain.renderPage(0).asAndroidBitmap()
            assertFalse("标题应放大加粗，与同文纯文本渲染不同", a.sameAs(b))
        } finally {
            md.close()
            plain.close()
        }
    }

    @Test fun oversizedTableRowDoesNotLoopForever() {
        val cell = "很长的单元格内容".repeat(200)
        val src = source("| a | b |\n|---|---|\n| $cell | $cell |\n")
        try {
            assertTrue(src.pageCount >= 1)
        } finally {
            src.close()
        }
    }
}
