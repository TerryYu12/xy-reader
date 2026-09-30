package com.xyreader.archive

import android.app.Application
import android.graphics.Paint
import android.text.Spanned
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.DisplayMetrics
import androidx.compose.ui.graphics.asAndroidBitmap
import com.xyreader.core.NovelFontWeight
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 在Android文本排版环境中验证分页，不以mock行高代替实际StaticLayout。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NovelRegressionTest {
    private val metrics = DisplayMetrics().apply {
        widthPixels = 480
        heightPixels = 800
        density = 1f
        scaledDensity = 1f
        densityDpi = 160
    }

    private fun source(
        size: Float,
        weight: NovelFontWeight = NovelFontWeight.NORMAL,
        firstLineIndent: Boolean = false,
        lineSpacing: Float = 1.5f,
        marginTopPx: Float = 64f,
        marginBottomPx: Float = 64f,
        marginLeftPx: Float = 48f,
        marginRightPx: Float = 48f,
        letterSpacingPx: Float = 0f,
    ): NovelPageSource = NovelPageSource(
        paragraphs = List(50) { i ->
            Paragraph("第${i}段：中文排版测试。字体变大以后，一页能容纳的字数应当减少。".repeat(8), i / 10)
        },
        chapterMarks = List(5) { ChapterMark("第${it + 1}章", it * 10) },
        style = NovelStyle(
            0xff222222.toInt(), size, 480, 800,
            fontWeight = weight,
            firstLineIndent = firstLineIndent,
            lineSpacingMultiplier = lineSpacing,
            marginTopPx = marginTopPx,
            marginBottomPx = marginBottomPx,
            marginLeftPx = marginLeftPx,
            marginRightPx = marginRightPx,
            letterSpacingPx = letterSpacingPx,
        ),
        displayMetrics = metrics,
    )

    @Test fun largerTextRequiresMorePagesAndKeepsChapterOrder() {
        val small = source(16f)
        val large = source(30f)
        try {
            assertTrue("字号变大应增加页数", large.pageCount > small.pageCount)
            assertEquals(5, large.chapters.size)
            assertTrue(large.chapters.zipWithNext().all { (a, b) -> a.startPage <= b.startPage })
            assertTrue(large.chapters.all { it.startPage in 0 until large.pageCount })
        } finally {
            small.close()
            large.close()
        }
    }

    @Test fun lineSpacingAndFourMarginsChangePagination() {
        val baseline = source(19f)
        val largerLeftMargin = source(19f, marginLeftPx = 80f)
        val largerRightMargin = source(19f, marginRightPx = 80f)
        val largerTopMargin = source(19f, marginTopPx = 88f)
        val largerBottomMargin = source(19f, marginBottomPx = 88f)
        val looseLineSpacing = source(19f, lineSpacing = 2f)
        try {
            assertTrue("左边距增大应缩窄正文并增加页数", largerLeftMargin.pageCount > baseline.pageCount)
            assertTrue("右边距增大应缩窄正文并增加页数", largerRightMargin.pageCount > baseline.pageCount)
            assertTrue("上边距增大应减少每页行数", largerTopMargin.pageCount > baseline.pageCount)
            assertTrue("下边距增大应减少每页行数", largerBottomMargin.pageCount > baseline.pageCount)
            assertTrue("行距增大应增加页数", looseLineSpacing.pageCount > baseline.pageCount)
        } finally {
            baseline.close()
            largerLeftMargin.close()
            largerRightMargin.close()
            largerTopMargin.close()
            largerBottomMargin.close()
            looseLineSpacing.close()
        }
    }

    @Test fun letterSpacingChangesRenderedNovelText() = runBlocking {
        val normal = source(19f)
        val spaced = source(19f, letterSpacingPx = 3f)
        try {
            val normalBitmap = normal.renderPage(0).asAndroidBitmap()
            val spacedBitmap = spaced.renderPage(0).asAndroidBitmap()
            assertFalse("字间距设置必须改变实际位图", normalBitmap.sameAs(spacedBitmap))
        } finally {
            normal.close()
            spaced.close()
        }
    }

    @Test fun firstAndLastPagesRenderVisibleText() = runBlocking {
        val book = source(19f)
        try {
            for (index in listOf(0, book.pageCount - 1)) {
                val bitmap = book.renderPage(index).asAndroidBitmap()
                assertEquals(480, bitmap.width)
                assertEquals(800, bitmap.height)
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("文字页不能全透明", pixels.any { it ushr 24 != 0 })
            }
        } finally { book.close() }
    }

    @Test fun commonChineseTxtEncodingsAreDecodedWithoutReplacementCharacters() {
        val text = "第一章 夜读\n窗外下着雨，书页翻到了下一章。"
        assertEquals(text, NovelTextExtractor.decodeTxt(text.toByteArray(Charsets.UTF_8)))
        assertEquals(text, NovelTextExtractor.decodeTxt(text.toByteArray(charset("GBK"))))
        assertEquals(text, NovelTextExtractor.decodeTxt(text.toByteArray(Charsets.UTF_16)))
    }

    @Test fun textAnchorSurvivesRepaginationWithoutSkippingTheReadingPosition() {
        val small = source(16f)
        val large = source(30f)
        try {
            val oldPage = small.pageCount / 2
            val anchor = small.pageStartCharOffset(oldPage)
            val newPage = large.pageForCharOffset(anchor)
            assertTrue(newPage in 0 until large.pageCount)
            assertTrue("重排后页首不能越过原阅读位置", large.pageStartCharOffset(newPage) <= anchor)
            if (newPage + 1 < large.pageCount) {
                assertTrue("锚点必须落在恢复页范围内", anchor < large.pageStartCharOffset(newPage + 1))
            }
            assertEquals(0, large.pageForCharOffset(-1))
            assertEquals(large.pageCount - 1, large.pageForCharOffset(Long.MAX_VALUE))
        } finally {
            small.close()
            large.close()
        }
    }

    @Test fun boldSettingChangesRenderedChineseText() = runBlocking {
        val normal = source(19f)
        val bold = source(19f, NovelFontWeight.BOLD)
        try {
            val normalBitmap = normal.renderPage(0).asAndroidBitmap()
            val boldBitmap = bold.renderPage(0).asAndroidBitmap()
            assertFalse("字重设置必须改变实际位图", normalBitmap.sameAs(boldBitmap))
        } finally {
            normal.close()
            bold.close()
        }
    }

    @Test fun firstLineIndentSpanAddsTwoCharMarginWithoutChangingText() {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 19f }

        // 普通段落：挂上 2 全角字符宽（≈2×字号 px）的仅首行边距
        val plain = buildIndentedParagraph(paint, "测试文本")
        val span = (plain as Spanned)
            .getSpans(0, plain.length, LeadingMarginSpan::class.java)
            .firstOrNull()
        assertNotNull("普通段落应挂 LeadingMarginSpan", span)
        assertEquals(38f, span!!.getLeadingMargin(true).toFloat(), 1f)
        assertEquals("缩进不得改动文本本身", "测试文本", plain.toString())

        // 「　　」开头的段落：已有 2 字缩进 → 不再叠加（不挂 span），文本原样
        val preset = buildIndentedParagraph(paint, "\u3000\u3000已有缩进")
        val presetSpans = (preset as Spanned)
            .getSpans(0, preset.length, LeadingMarginSpan::class.java)
        assertTrue("已有两字缩进时不应叠加", presetSpans.isEmpty())
        assertEquals("\u3000\u3000已有缩进", preset.toString())

        // 半角空格开头：按总宽对齐（2 字符宽 - 空格实测宽）
        val half = buildIndentedParagraph(paint, " 半角空格")
        val halfSpan = (half as Spanned)
            .getSpans(0, half.length, LeadingMarginSpan::class.java)
            .firstOrNull()
        assertNotNull(halfSpan)
        assertEquals(
            38f - paint.measureText(" "),
            halfSpan!!.getLeadingMargin(true).toFloat(),
            1f,
        )
    }

    @Test fun indentSettingKeepsPageTextAndAnchorClean() = runBlocking {
        val indented = source(19f, firstLineIndent = true)
        try {
            assertTrue("缩进模式应能正常分页", indented.pageCount > 0)
            assertEquals("页 0 字符锚点不应偏移", 0L, indented.pageStartCharOffset(0))
            val text = indented.pageText(0)
            assertFalse("「复制文字」不应混入缩进填充字符", text.contains('\u3000'))
            val bitmap = indented.renderPage(0).asAndroidBitmap()
            assertEquals(480, bitmap.width)
        } finally {
            indented.close()
        }
    }
}
