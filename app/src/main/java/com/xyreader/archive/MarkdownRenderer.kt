package com.xyreader.archive

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import kotlin.math.roundToInt

/**
 * Markdown 段落的版式 / 绘制（供 [NovelPageSource] 使用，仅处理 [Paragraph.md] 非空的段落）。
 *
 * 版式规则（em = 正文字号 px，lh = 统一行高 px）：
 * - 标题：字号 1.6 / 1.4 / 1.2 / 1.1 / 1.0 / 0.9 倍 + 粗体，1~2 级下方画细线；
 * - 代码块：等宽小字 + 圆角底色块；行内代码：等宽 + 浅底；
 * - 块引用：每层左缩进 1em，左侧画竖线，文字略淡；
 * - 列表：每级缩进 1.5em，符号后悬挂缩进；
 * - 表格：每行一个块，列宽按内容占比分配，画网格线，表头行加粗 + 浅底；
 * - 分割线：整行细线。
 * 所有块自带上下间距（计入分页高度），因此分页按像素累加而非固定行高。
 */
internal class MarkdownRenderer(
    private val base: TextPaint,
    private val style: NovelStyle,
    private val contentWidth: Int,
    private val lineHeightPx: Int,
) {
    private val em: Float = base.textSize
    private val textColor: Int = style.textColorArgb
    private val lh: Float = lineHeightPx.toFloat()

    private val linkColor: Int =
        if (luminance(textColor) > 0.5f) 0xFF7AB8FF.toInt() else 0xFF1565C0.toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (em * 0.07f).coerceAtLeast(1.5f)
    }

    /** 表格行排版缓存：key = 段落序号 */
    private val tableCache = LruCache<Int, TableRowLayout>(64)

    private class TableRowLayout(
        val cells: List<StaticLayout>,
        val colStarts: IntArray,
        val tableWidth: Int,
        val rowHeight: Int,
    )

    // ---------- 间距 ----------

    private fun before(md: MdBlockInfo): Int = (lh * when (md.kind) {
        MdKind.HEADING -> when (md.level) { 1 -> 0.7f; 2 -> 0.6f; 3 -> 0.5f; else -> 0.4f }
        MdKind.CODE_BLOCK -> 0.2f
        MdKind.RULE -> 0.3f
        MdKind.TABLE_ROW -> if (md.header) 0.3f else 0f
        else -> 0f
    }).roundToInt()

    private fun after(md: MdBlockInfo): Int = (lh * when (md.kind) {
        MdKind.PARAGRAPH -> if (md.depth > 0) 0.3f else 0.45f
        MdKind.HEADING -> 0.25f
        MdKind.LIST_ITEM -> if (md.groupEnd) 0.45f else 0.12f
        MdKind.CODE_BLOCK -> 0.45f
        MdKind.RULE -> 0.3f
        MdKind.TABLE_ROW -> if (md.groupEnd) 0.5f else 0f
    }).roundToInt()

    /** 代码块上下内边距（底色块内部） */
    private fun padV(md: MdBlockInfo): Int = if (md.kind == MdKind.CODE_BLOCK) (lh * 0.35f).roundToInt() else 0

    private fun padH(md: MdBlockInfo): Int = if (md.kind == MdKind.CODE_BLOCK) (em * 0.6f).roundToInt() else 0

    private fun quoteIndent(md: MdBlockInfo): Int = (md.quoteDepth * em).roundToInt()

    private fun indentLeft(md: MdBlockInfo): Int =
        quoteIndent(md) + (md.depth * em * 1.5f).roundToInt()

    private fun textWidth(md: MdBlockInfo): Int =
        (contentWidth - indentLeft(md) - 2 * padH(md)).coerceAtLeast((em * 2).roundToInt().coerceAtLeast(1))

    /** 该块是否由自定义方式绘制且分页上只算一个"行"（分割线 / 表格行） */
    private fun isSingleBlock(md: MdBlockInfo) = md.kind == MdKind.RULE || md.kind == MdKind.TABLE_ROW

    // ---------- 排版 ----------

    /** 文本块的 StaticLayout（分割线 / 表格行返回整行纯文本排版，仅用于字符偏移） */
    fun buildLayout(p: Paragraph): StaticLayout {
        val md = p.md!!
        if (isSingleBlock(md)) {
            return layoutOf(SpannableStringBuilder(p.text), base, contentWidth, Layout.Alignment.ALIGN_NORMAL)
        }
        val sb: SpannableStringBuilder =
            if (md.kind == MdKind.PARAGRAPH && style.firstLineIndent && md.quoteDepth == 0 && md.depth == 0 &&
                p.text.isNotEmpty()
            ) {
                SpannableStringBuilder(buildIndentedParagraph(base, p.text))
            } else {
                SpannableStringBuilder(p.text)
            }
        val len = sb.length
        val full = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        // 段落级样式先于行内样式设置，行内样式覆盖其上
        if (len > 0) {
            when (md.kind) {
                MdKind.HEADING -> {
                    sb.setSpan(RelativeSizeSpan(headingScale(md.level)), 0, len, full)
                    sb.setSpan(StyleSpan(Typeface.BOLD), 0, len, full)
                    if (md.level >= 6) sb.setSpan(ForegroundColorSpan(withAlpha(textColor, 0xB0)), 0, len, full)
                }
                MdKind.CODE_BLOCK -> {
                    sb.setSpan(TypefaceSpan("monospace"), 0, len, full)
                    sb.setSpan(RelativeSizeSpan(0.9f), 0, len, full)
                }
                MdKind.LIST_ITEM -> {
                    val hang = base.measureText(p.text, 0, md.prefixLen.coerceIn(0, len)).roundToInt()
                    sb.setSpan(LeadingMarginSpan.Standard(0, hang), 0, len, Spannable.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                else -> Unit
            }
            if (md.quoteDepth > 0) {
                sb.setSpan(ForegroundColorSpan(withAlpha(textColor, 0xCC)), 0, len, full)
            }
        }
        applySpans(sb, md.spans)
        return layoutOf(sb, base, textWidth(md), Layout.Alignment.ALIGN_NORMAL)
    }

    private fun layoutOf(
        text: CharSequence, paint: TextPaint, width: Int, align: Layout.Alignment,
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(align)
            .setLineSpacing(0f, style.lineSpacingMultiplier.coerceIn(0.8f, 2.5f))
            .setIncludePad(true)
            .build()

    private fun applySpans(sb: SpannableStringBuilder, spans: List<MdSpan>) {
        val len = sb.length
        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        for (s in spans) {
            val a = s.start.coerceIn(0, len)
            val b = s.end.coerceIn(0, len)
            if (a >= b) continue
            when (s.type) {
                MdSpanType.BOLD -> sb.setSpan(StyleSpan(Typeface.BOLD), a, b, flag)
                MdSpanType.ITALIC -> sb.setSpan(StyleSpan(Typeface.ITALIC), a, b, flag)
                MdSpanType.STRIKE -> sb.setSpan(StrikethroughSpan(), a, b, flag)
                MdSpanType.CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), a, b, flag)
                    sb.setSpan(RelativeSizeSpan(0.92f), a, b, flag)
                    sb.setSpan(BackgroundColorSpan(withAlpha(textColor, 0x24)), a, b, flag)
                }
                MdSpanType.LINK -> {
                    sb.setSpan(ForegroundColorSpan(linkColor), a, b, flag)
                    sb.setSpan(UnderlineSpan(), a, b, flag)
                }
                MdSpanType.IMAGE -> {
                    sb.setSpan(ForegroundColorSpan(withAlpha(textColor, 0x99)), a, b, flag)
                    sb.setSpan(StyleSpan(Typeface.ITALIC), a, b, flag)
                }
            }
        }
    }

    private fun headingScale(level: Int) = when (level) {
        1 -> 1.6f; 2 -> 1.4f; 3 -> 1.2f; 4 -> 1.1f; 5 -> 1.0f; else -> 0.9f
    }

    // ---------- 高度 ----------

    /**
     * 每个"可分页行"的像素高度（含块上下间距）：文本块 = layout 各行高，表格行 / 分割线 = 单个元素。
     * 数组长度即该块在分页中的行数。
     */
    fun lineHeights(key: Int, p: Paragraph, layout: StaticLayout): IntArray {
        val md = p.md!!
        val heights: IntArray = when (md.kind) {
            MdKind.RULE -> intArrayOf((lh * 0.4f).roundToInt().coerceAtLeast(2))
            MdKind.TABLE_ROW -> intArrayOf(tableRow(key, p).rowHeight)
            else -> IntArray(layout.lineCount) { layout.getLineTop(it + 1) - layout.getLineTop(it) }
        }
        if (heights.isEmpty()) return intArrayOf(lineHeightPx)
        heights[0] += before(md) + padV(md)
        heights[heights.lastIndex] += padV(md) + after(md)
        return heights
    }

    // ---------- 表格 ----------

    private fun tableRow(key: Int, p: Paragraph): TableRowLayout {
        tableCache.get(key)?.let { return it }
        val md = p.md!!
        val tableWidth = (contentWidth - quoteIndent(md)).coerceAtLeast(1)
        val n = md.cells.size.coerceAtLeast(1)
        val fractions = if (md.colFractions.size == n) md.colFractions else List(n) { 1f / n }
        val colStarts = IntArray(n + 1)
        var acc = 0f
        for (c in 0 until n) {
            colStarts[c] = (acc * tableWidth).roundToInt()
            acc += fractions[c]
        }
        colStarts[n] = tableWidth
        val padHc = (em * 0.5f).roundToInt()
        val padVc = (em * 0.3f).roundToInt()
        var maxH = 0
        val layouts = md.cells.mapIndexed { c, cell ->
            val sb = SpannableStringBuilder(cell.text)
            if (md.header && sb.isNotEmpty()) {
                sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            applySpans(sb, cell.spans)
            val width = (colStarts[c + 1] - colStarts[c] - 2 * padHc).coerceAtLeast(1)
            val align = when (cell.align) {
                1 -> Layout.Alignment.ALIGN_CENTER
                2 -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            layoutOf(sb, base, width, align).also { maxH = maxOf(maxH, it.height) }
        }
        val row = TableRowLayout(layouts, colStarts, tableWidth, maxH + 2 * padVc)
        tableCache.put(key, row)
        return row
    }

    // ---------- 绘制 ----------

    /**
     * 画一个页内片段到 [top]（槽位顶，含块上下间距）。
     * [left] / [right] 是页面内容区左右边界（已扣页边距）。
     */
    fun draw(
        canvas: Canvas, key: Int, p: Paragraph, layout: StaticLayout, frag: PageFragment,
        left: Float, right: Float, top: Float,
    ) {
        val md = p.md!!
        val total = if (isSingleBlock(md)) 1 else layout.lineCount
        val first = frag.startLine == 0
        val last = frag.startLine + frag.lineCount >= total
        val bgTop = top + if (first) before(md) else 0
        val bgBottom = top + frag.heightPx - if (last) after(md) else 0

        when (md.kind) {
            MdKind.RULE -> {
                linePaint.color = withAlpha(textColor, 0x66)
                val y = (bgTop + bgBottom) / 2f
                canvas.drawLine(left + quoteIndent(md), y, right, y, linePaint)
                drawQuoteBars(canvas, md, left, bgTop, bgBottom)
            }
            MdKind.TABLE_ROW -> drawTableRow(canvas, key, p, left, bgTop, bgBottom)
            else -> drawTextBlock(canvas, md, layout, frag, left, right, top, bgTop, bgBottom, first, last)
        }
    }

    private fun drawTextBlock(
        canvas: Canvas, md: MdBlockInfo, layout: StaticLayout, frag: PageFragment,
        left: Float, right: Float, top: Float, bgTop: Float, bgBottom: Float, first: Boolean, last: Boolean,
    ) {
        val x0 = left + indentLeft(md)
        if (md.kind == MdKind.CODE_BLOCK) {
            fillPaint.color = withAlpha(textColor, 0x1A)
            val r = em * 0.3f
            canvas.drawRoundRect(RectF(x0, bgTop, right, bgBottom), r, r, fillPaint)
        }
        drawQuoteBars(canvas, md, left, bgTop, bgBottom)

        val textTop = top + if (first) before(md) + padV(md) else 0
        val textBottom = top + frag.heightPx - if (last) padV(md) + after(md) else 0
        canvas.save()
        canvas.clipRect(left, textTop, right, textBottom.coerceAtLeast(textTop))
        canvas.translate(x0 + padH(md), textTop - layout.getLineTop(frag.startLine))
        layout.draw(canvas)
        canvas.restore()

        if (md.kind == MdKind.HEADING && md.level <= 2 && last) {
            linePaint.color = withAlpha(textColor, 0x40)
            val y = bgBottom - after(md) * 0.35f
            canvas.drawLine(x0, y, right, y, linePaint)
        }
    }

    private fun drawQuoteBars(canvas: Canvas, md: MdBlockInfo, left: Float, bgTop: Float, bgBottom: Float) {
        if (md.quoteDepth <= 0) return
        fillPaint.color = withAlpha(textColor, 0x66)
        val w = (em * 0.15f).coerceAtLeast(2f)
        for (d in 0 until md.quoteDepth) {
            val x = left + d * em + em * 0.2f
            canvas.drawRect(x, bgTop, x + w, bgBottom, fillPaint)
        }
    }

    private fun drawTableRow(
        canvas: Canvas, key: Int, p: Paragraph, left: Float, bgTop: Float, bgBottom: Float,
    ) {
        val md = p.md!!
        val row = tableRow(key, p)
        val x0 = left + quoteIndent(md)
        val xEnd = x0 + row.tableWidth
        if (md.header) {
            fillPaint.color = withAlpha(textColor, 0x18)
            canvas.drawRect(x0, bgTop, xEnd, bgBottom, fillPaint)
        }
        val padHc = (em * 0.5f)
        val padVc = (em * 0.3f)
        row.cells.forEachIndexed { c, cell ->
            canvas.save()
            canvas.clipRect(x0 + row.colStarts[c], bgTop, x0 + row.colStarts[c + 1], bgBottom)
            canvas.translate(x0 + row.colStarts[c] + padHc, bgTop + padVc)
            cell.draw(canvas)
            canvas.restore()
        }
        linePaint.color = withAlpha(textColor, 0x55)
        if (md.header) canvas.drawLine(x0, bgTop, xEnd, bgTop, linePaint)
        canvas.drawLine(x0, bgBottom, xEnd, bgBottom, linePaint)
        for (c in 0..row.cells.size) {
            val x = x0 + row.colStarts[c.coerceAtMost(row.colStarts.lastIndex)]
            canvas.drawLine(x, bgTop, x, bgBottom, linePaint)
        }
        drawQuoteBars(canvas, md, left, bgTop, bgBottom)
    }

    fun clearCaches() = tableCache.evictAll()

    private companion object {
        fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

        fun luminance(color: Int): Float {
            val r = (color shr 16 and 0xFF) / 255f
            val g = (color shr 8 and 0xFF) / 255f
            val b = (color and 0xFF) / 255f
            return 0.299f * r + 0.587f * g + 0.114f * b
        }
    }
}
