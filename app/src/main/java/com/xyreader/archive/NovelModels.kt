package com.xyreader.archive

/**
 * 章节标记：章首所在的全局段落序号（由 [NovelPageSource] 分页后映射为页区间）。
 * 与 core.Chapter 解耦：core.Chapter 需要"页区间"，只有分页后才能算出。
 */
data class ChapterMark(val title: String, val paragraphIndex: Int)

/**
 * 一个文字段落：文本 + 所属章节序号（各格式解析器填充）。
 * [md] 仅 Markdown 解析器填充：[text] 始终是去掉标记后的纯文本（字符偏移 / 复制文字 / 字数统计
 * 全部基于它），[md] 描述这段文字该用什么版式与行内样式画出来。TXT / EPUB / MOBI 恒为 null。
 */
data class Paragraph(val text: String, val chapterIndex: Int, val md: MdBlockInfo? = null)

/** Markdown 行内样式类型 */
enum class MdSpanType { BOLD, ITALIC, STRIKE, CODE, LINK, IMAGE }

/** 行内样式区间：[start, end) 为所属文本（段落或单元格）内的字符偏移 */
data class MdSpan(val type: MdSpanType, val start: Int, val end: Int)

/** Markdown 块类型 */
enum class MdKind { PARAGRAPH, HEADING, CODE_BLOCK, LIST_ITEM, RULE, TABLE_ROW }

/** 表格单元格：自身文本 + 行内样式；[align] 0=左 1=中 2=右 */
data class MdCell(val text: String, val spans: List<MdSpan>, val align: Int = 0)

/**
 * 一个 Markdown 块的版式信息。
 * - HEADING：[level] 1..6；
 * - LIST_ITEM：[depth] 嵌套层级，[prefixLen] = 文本开头列表符号（"• " / "1. " / "☐ "）的字符数，用于悬挂缩进；
 *   [groupEnd] = 该项是整个列表的最后一项（其后留段间距）；
 * - PARAGRAPH：[depth] > 0 表示列表项内的续段（缩进对齐）；
 * - CODE_BLOCK：整块代码一个段落，行以 "\n" 分隔；
 * - TABLE_ROW：[cells] 各列单元格，[colFractions] 各列宽度占比（同一表格共享），[header] 是否表头行，
 *   [groupEnd] 是否表格最后一行；
 * - [quoteDepth] > 0：位于块引用内（左侧画竖线并缩进）。
 */
data class MdBlockInfo(
    val kind: MdKind,
    val level: Int = 0,
    val depth: Int = 0,
    val quoteDepth: Int = 0,
    val prefixLen: Int = 0,
    val spans: List<MdSpan> = emptyList(),
    val cells: List<MdCell> = emptyList(),
    val colFractions: List<Float> = emptyList(),
    val header: Boolean = false,
    val groupEnd: Boolean = false,
)
