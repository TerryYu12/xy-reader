package com.xyreader.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Markdown 解析器（纯 JVM）：块结构、行内样式区间、章节标记 */
class MarkdownParserTest {

    private fun parse(src: String) = MarkdownParser.parse(src)

    private fun Paragraph.spanTexts(type: MdSpanType) =
        md!!.spans.filter { it.type == type }.map { text.substring(it.start, it.end) }

    @Test fun headingsBecomeChaptersUpToLevelThree() {
        val (paras, marks) = parse("# 一\n\n## 二\n\n### 三\n\n#### 四\n\n正文")
        assertEquals(listOf("一", "二", "三"), marks.map { it.title })
        assertEquals(listOf(1, 2, 3, 4), paras.take(4).map { it.md!!.level })
        assertTrue(paras.take(4).all { it.md!!.kind == MdKind.HEADING })
        // 四级标题不产生章节，正文归属第三章
        assertEquals(2, paras.last().chapterIndex)
    }

    @Test fun setextHeadings() {
        val (paras, marks) = parse("大标题\n======\n\n小标题\n------\n")
        assertEquals(listOf(1, 2), paras.map { it.md!!.level })
        assertEquals(listOf("大标题", "小标题"), marks.map { it.title })
    }

    @Test fun inlineStylesAreStrippedAndRangesRecorded() {
        val (paras, _) = parse("普通 **粗** *斜* ~~删~~ `码` [链接](http://a.b) ![图](x.png)")
        val p = paras.single()
        assertEquals("普通 粗 斜 删 码 链接 [图片: 图]", p.text)
        assertEquals(listOf("粗"), p.spanTexts(MdSpanType.BOLD))
        assertEquals(listOf("斜"), p.spanTexts(MdSpanType.ITALIC))
        assertEquals(listOf("删"), p.spanTexts(MdSpanType.STRIKE))
        assertEquals(listOf("码"), p.spanTexts(MdSpanType.CODE))
        assertEquals(listOf("链接"), p.spanTexts(MdSpanType.LINK))
        assertEquals(listOf("[图片: 图]"), p.spanTexts(MdSpanType.IMAGE))
    }

    @Test fun boldItalicAndNesting() {
        val p = parse("***both*** and **bold *it* more**").first.single()
        assertEquals("both and bold it more", p.text)
        assertEquals(listOf("both", "bold it more"), p.spanTexts(MdSpanType.BOLD))
        assertEquals(listOf("both", "it"), p.spanTexts(MdSpanType.ITALIC))
    }

    @Test fun snakeCaseAndEscapesAreLiteral() {
        val p = parse("snake_case_name 与 \\*转义\\* 和 a * b").first.single()
        assertEquals("snake_case_name 与 *转义* 和 a * b", p.text)
        assertTrue(p.md!!.spans.isEmpty())
    }

    @Test fun codeSpanKeepsMarkupLiteral() {
        val p = parse("用 `**x**` 表示").first.single()
        assertEquals("用 **x** 表示", p.text)
        assertEquals(listOf("**x**"), p.spanTexts(MdSpanType.CODE))
        assertTrue(p.spanTexts(MdSpanType.BOLD).isEmpty())
    }

    @Test fun softBreaksMergeAndHardBreaksSplit() {
        val p = parse("第一行\n第二行\nline one\nline two  \nafter break").first.single()
        assertEquals("第一行第二行line one line two\nafter break", p.text)
    }

    @Test fun nestedListsTaskListsAndOrdered() {
        val (paras, _) = parse("- a\n  - b\n    - c\n- [ ] todo\n- [x] done\n\n1. one\n2. two\n")
        val md = paras.map { it.md!! }
        assertTrue(md.all { it.kind == MdKind.LIST_ITEM })
        assertEquals(listOf(0, 1, 2, 0, 0, 0, 0), md.map { it.depth })
        assertEquals(listOf("• a", "◦ b", "▪ c", "☐ todo", "☑ done", "1. one", "2. two"), paras.map { it.text })
        assertEquals(2, md[0].prefixLen)
        // 仅整个列表的最后一项带 groupEnd
        assertEquals(listOf(false, false, false, false, false, false, true), md.map { it.groupEnd })
    }

    @Test fun blockQuoteDepth() {
        val (paras, _) = parse("> 一层\n>> 二层\n\n普通")
        assertEquals(listOf(1, 2, 0), paras.map { it.md!!.quoteDepth })
    }

    @Test fun fencedAndIndentedCodeBlocks() {
        val (paras, _) = parse("```kotlin\nfun main() {\n    println(1)\n}\n```\n\n    indented\n    code\n")
        assertEquals(MdKind.CODE_BLOCK, paras[0].md!!.kind)
        assertEquals("fun main() {\n    println(1)\n}", paras[0].text)
        assertEquals(MdKind.CODE_BLOCK, paras[1].md!!.kind)
        assertEquals("indented\ncode", paras[1].text)
    }

    @Test fun gfmTableWithAlignment() {
        val (paras, _) = parse("| 名称 | 说明 | 数 |\n|:--|:-:|--:|\n| a | b | 1 |\n| `x|y` | 长一点的说明 | 22 |\n")
        assertEquals(3, paras.size)
        assertTrue(paras.all { it.md!!.kind == MdKind.TABLE_ROW })
        assertEquals(listOf(true, false, false), paras.map { it.md!!.header })
        assertEquals(listOf(false, false, true), paras.map { it.md!!.groupEnd })
        assertEquals(listOf(0, 1, 2), paras[0].md!!.cells.map { it.align })
        assertEquals("x|y", paras[2].md!!.cells[0].text)
        assertEquals(1f, paras[0].md!!.colFractions.sum(), 0.001f)
    }

    @Test fun horizontalRuleFrontMatterAndReferenceDefinitions() {
        val (paras, marks) = parse("---\ntitle: x\n---\n正文\n\n***\n\n[ref]: http://x\n\n结尾")
        assertEquals(listOf(MdKind.PARAGRAPH, MdKind.RULE, MdKind.PARAGRAPH), paras.map { it.md!!.kind })
        assertEquals(listOf("正文", "", "结尾"), paras.map { it.text })
        assertEquals("正文", marks.single().title)
    }

    @Test fun htmlEntitiesAutolinksAndBreaks() {
        val p = parse("a &amp; b &#65; <br>下行 <https://x.org> https://y.org/p.").first.single()
        assertEquals("a & b A \n下行 https://x.org https://y.org/p.", p.text)
        assertEquals(listOf("https://x.org", "https://y.org/p"), p.spanTexts(MdSpanType.LINK))
    }

    @Test fun emptyInputStillYieldsOneChapter() {
        val (paras, marks) = parse("")
        assertTrue(paras.isEmpty())
        assertEquals(1, marks.size)
    }

    @Test fun orderedNumberMidParagraphIsNotAList() {
        val (paras, _) = parse("春节在\n2024. 年举行")
        assertEquals(MdKind.PARAGRAPH, paras.single().md!!.kind)
        assertFalse(paras.single().text.startsWith("2024."))
    }
}
