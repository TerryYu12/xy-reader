package com.xyreader.archive

/**
 * Markdown 解析器（纯 Kotlin、无 Android 依赖，可直接单元测试）：
 * Markdown 全文 → [Paragraph] 序列（文本为去标记后的纯文本，版式在 [Paragraph.md]）+ [ChapterMark]。
 *
 * 支持：ATX / Setext 标题、段落（软换行合并、硬换行）、粗体 / 斜体 / 删除线 / 行内代码 / 链接 /
 * 图片占位 / 自动链接 / 行内 HTML 清理 / 转义与实体、围栏与缩进代码块、有序 / 无序 / 任务列表（多级嵌套）、
 * 块引用（多级）、分割线、GFM 表格（含对齐）、YAML 头、链接引用定义。
 * 标题 1~3 级生成章节（目录跳转），其余级别只改变版式。
 */
object MarkdownParser {

    fun parse(text: String): Pair<List<Paragraph>, List<ChapterMark>> = Builder().run(text)

    // ------------------------------------------------------------------
    // 块级
    // ------------------------------------------------------------------

    private val HR = Regex("^((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
    private val ATX = Regex("^(#{1,6})(?:\\s+(.*?))?(?:\\s+#+)?\\s*$")
    private val REF_DEF = Regex("^\\[[^\\]]+]:\\s*\\S+.*$")
    private val BULLET = Regex("^([-*+])\\s+(.*)$")
    private val ORDERED = Regex("^(\\d{1,9})([.)])\\s+(.*)$")
    private val TABLE_SEP_CELL = Regex("^:?-+:?$")
    private val SETEXT_H1 = Regex("^=+$")
    private val SETEXT_H2 = Regex("^-+$")

    private class Pending(
        val isListItem: Boolean,
        val quoteDepth: Int,
        val depth: Int,
        val prefix: String,
    ) {
        val raw = StringBuilder()
        var afterBreak = false
    }

    private class Builder {
        val paragraphs = mutableListOf<Paragraph>()
        val marks = mutableListOf<ChapterMark>()
        var currentChapter = -1
        var pending: Pending? = null
        val listIndents = ArrayList<Int>()

        fun run(text: String): Pair<List<Paragraph>, List<ChapterMark>> {
            val lines = text.lines().map(::expandLeadingTabs)
            var i = 0
            // YAML front matter：首行 --- 且存在收尾 --- / ...
            if (lines.firstOrNull()?.trim() == "---") {
                val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" || lines[it].trim() == "..." }
                if (end != null) i = end + 1
            }
            while (i < lines.size) i = step(lines, i)
            flush()
            markListEnd(null)
            if (marks.isEmpty()) marks += ChapterMark(title = "正文", paragraphIndex = 0)
            return paragraphs to marks
        }

        /** 处理 lines[i] 起的一个块，返回下一个待处理行号 */
        private fun step(lines: List<String>, i: Int): Int {
            val (qd, content) = stripQuote(lines[i])
            val indent = content.length - content.trimStart().length
            val t = content.trim()

            val p = pending
            if (p != null && p.quoteDepth != qd) flush()

            // —— 围栏代码块 ——
            val fence = if (t.startsWith("```")) "```" else if (t.startsWith("~~~")) "~~~" else null
            if (fence != null) {
                flush()
                val body = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size) {
                    val inner = stripQuote(lines[j]).second
                    if (inner.trim().startsWith(fence)) break
                    body += inner.removeIndent(indent)
                    j++
                }
                emitCode(body, qd)
                return j + 1
            }

            if (t.isEmpty()) {
                flush()
                return i + 1
            }

            // —— 缩进代码块（不在列表内、不接在段落后） ——
            if (indent >= 4 && pending == null && listIndents.isEmpty()) {
                val body = mutableListOf<String>()
                var j = i
                while (j < lines.size) {
                    val inner = stripQuote(lines[j]).second
                    if (inner.isBlank()) { body += ""; j++; continue }
                    if (inner.length - inner.trimStart().length < 4) break
                    body += inner.removeIndent(4)
                    j++
                }
                emitCode(body, qd)
                return j
            }

            // —— Setext 标题 ——
            val cur = pending
            if (cur != null && !cur.isListItem && cur.depth == 0) {
                if (SETEXT_H1.matches(t)) { emitHeadingFromPending(1, qd); return i + 1 }
                if (SETEXT_H2.matches(t)) { emitHeadingFromPending(2, qd); return i + 1 }
            }

            // —— 分割线 ——
            if (HR.matches(t)) {
                flush()
                listIndents.clear()
                emit(Paragraph("", ensureChapter(), MdBlockInfo(MdKind.RULE, quoteDepth = qd)))
                return i + 1
            }

            // —— ATX 标题 ——
            val atx = ATX.matchEntire(t)
            if (atx != null) {
                flush()
                listIndents.clear()
                emitHeading(atx.groupValues[1].length, atx.groupValues[2], qd)
                return i + 1
            }

            // —— 链接引用定义 ——
            if (pending == null && REF_DEF.matches(t)) return i + 1

            // —— 表格 ——
            if (t.contains('|') && i + 1 < lines.size) {
                val sepCells = splitRow(stripQuote(lines[i + 1]).second.trim())
                val headCells = splitRow(t)
                if (sepCells.isNotEmpty() && sepCells.all { TABLE_SEP_CELL.matches(it.trim()) } &&
                    stripQuote(lines[i + 1]).second.contains('-') && headCells.size == sepCells.size
                ) {
                    flush()
                    listIndents.clear()
                    return emitTable(lines, i, headCells, sepCells, qd)
                }
            }

            // —— 列表项 ——
            val bullet = BULLET.matchEntire(t)
            val ordered = ORDERED.matchEntire(t)
            val canStartList = bullet != null ||
                (ordered != null && (pending == null || pending?.isListItem == true || ordered.groupValues[1] == "1"))
            if (canStartList) {
                flush()
                while (listIndents.isNotEmpty() && indent < listIndents.last()) listIndents.removeAt(listIndents.lastIndex)
                if (listIndents.isEmpty() || indent > listIndents.last()) listIndents += indent
                val depth = listIndents.size - 1
                var rest: String
                var prefix: String
                if (bullet != null) {
                    rest = bullet.groupValues[2]
                    prefix = BULLETS[depth % BULLETS.size] + " "
                } else {
                    rest = ordered!!.groupValues[3]
                    prefix = ordered.groupValues[1] + ordered.groupValues[2].replace(')', '.') + " "
                }
                if (rest.startsWith("[ ] ") || rest == "[ ]") { prefix = "☐ "; rest = rest.drop(4) }
                else if (rest.startsWith("[x] ") || rest.startsWith("[X] ") || rest == "[x]" || rest == "[X]") {
                    prefix = "☑ "; rest = rest.drop(4)
                }
                val np = Pending(true, qd, depth, prefix)
                np.raw.append(rest.trim())
                pending = np
                if (hardBreak(content)) { np.raw.append('\n'); np.afterBreak = true }
                return i + 1
            }

            // —— 普通文本行 ——
            val np = pending
            if (np != null) {
                append(np, t)
                if (hardBreak(content)) { np.raw.append('\n'); np.afterBreak = true }
                return i + 1
            }
            // 新段落：缩进 > 0 且处于列表内 → 列表项续段；否则结束列表
            var depth = 0
            if (listIndents.isNotEmpty()) {
                if (indent > 0) depth = listIndents.size else listIndents.clear()
            }
            val para = Pending(false, qd, depth, "")
            para.raw.append(t)
            pending = para
            if (hardBreak(content)) { para.raw.append('\n'); para.afterBreak = true }
            return i + 1
        }

        private fun hardBreak(content: String) = content.endsWith("  ") || content.endsWith("\\")

        private fun append(p: Pending, line: String) {
            val raw = p.raw
            val piece = if (line.endsWith("\\")) line.dropLast(1) else line
            if (raw.isNotEmpty() && !p.afterBreak) {
                if (!(isCjk(raw.last()) || (piece.isNotEmpty() && isCjk(piece.first())))) raw.append(' ')
            }
            p.afterBreak = false
            raw.append(piece)
        }

        private fun flush() {
            val p = pending ?: return
            pending = null
            val src = p.raw.toString().trim('\n', ' ')
            if (src.isBlank()) return
            val inline = MdInline.parse(src)
            if (inline.text.isBlank()) return
            if (p.isListItem) {
                val shift = p.prefix.length
                val spans = inline.spans.map { it.copy(start = it.start + shift, end = it.end + shift) }
                emit(
                    Paragraph(
                        p.prefix + inline.text, ensureChapter(),
                        MdBlockInfo(
                            MdKind.LIST_ITEM, depth = p.depth, quoteDepth = p.quoteDepth,
                            prefixLen = shift, spans = spans,
                        ),
                    ),
                )
            } else {
                emit(
                    Paragraph(
                        inline.text, ensureChapter(),
                        MdBlockInfo(MdKind.PARAGRAPH, depth = p.depth, quoteDepth = p.quoteDepth, spans = inline.spans),
                    ),
                )
            }
        }

        private fun emitHeadingFromPending(level: Int, qd: Int) {
            val p = pending ?: return
            pending = null
            emitHeading(level, p.raw.toString().trim('\n', ' '), qd)
        }

        private fun emitHeading(level: Int, rawText: String, qd: Int) {
            val inline = MdInline.parse(rawText.trim())
            if (inline.text.isBlank()) return
            if (level <= 3) {
                marks += ChapterMark(title = inline.text, paragraphIndex = paragraphs.size)
                currentChapter = marks.size - 1
            }
            emit(
                Paragraph(
                    inline.text, ensureChapter(),
                    MdBlockInfo(MdKind.HEADING, level = level, quoteDepth = qd, spans = inline.spans),
                ),
            )
        }

        private fun emitCode(body: List<String>, qd: Int) {
            val lines = body.map { it.trimEnd() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }
            if (lines.isEmpty()) return
            emit(
                Paragraph(
                    lines.joinToString("\n"), ensureChapter(),
                    MdBlockInfo(MdKind.CODE_BLOCK, quoteDepth = qd),
                ),
            )
        }

        private fun emitTable(
            lines: List<String>, start: Int, head: List<String>, sep: List<String>, qd: Int,
        ): Int {
            val cols = head.size
            val aligns = sep.map {
                val c = it.trim()
                when {
                    c.startsWith(":") && c.endsWith(":") -> 1
                    c.endsWith(":") -> 2
                    else -> 0
                }
            }
            val rows = mutableListOf<List<String>>()
            rows += head
            var j = start + 2
            while (j < lines.size) {
                val (q2, inner) = stripQuote(lines[j])
                val it = inner.trim()
                if (it.isEmpty() || !it.contains('|') || q2 != qd) break
                rows += splitRow(it)
                j++
            }
            val parsed = rows.map { row ->
                (0 until cols).map { c ->
                    val inline = MdInline.parse(row.getOrElse(c) { "" }.trim())
                    MdCell(inline.text, inline.spans, aligns[c])
                }
            }
            // 列宽占比：按各列最宽内容（CJK 计 2，拉丁字母实际约 1.1）加单元格内边距估算，夹取后归一化
            val weights = (0 until cols).map { c ->
                (parsed.map { displayWidth(it[c].text) }.max() * 1.15f + 3f).coerceIn(7f, 34f)
            }
            val total = weights.sum()
            val fractions = weights.map { it / total }
            parsed.forEachIndexed { r, cells ->
                emit(
                    Paragraph(
                        cells.joinToString("  |  ") { it.text }, ensureChapter(),
                        MdBlockInfo(
                            MdKind.TABLE_ROW, quoteDepth = qd, cells = cells, colFractions = fractions,
                            header = r == 0, groupEnd = r == parsed.lastIndex,
                        ),
                    ),
                )
            }
            return j
        }

        private fun ensureChapter(): Int {
            if (currentChapter < 0) {
                marks += ChapterMark(title = "正文", paragraphIndex = paragraphs.size)
                currentChapter = marks.size - 1
            }
            return currentChapter
        }

        private fun emit(p: Paragraph) {
            markListEnd(p.md?.kind)
            paragraphs += p
        }

        /** 新块不是列表项时，上一个列表项即列表末项：标记 groupEnd（其后留段间距） */
        private fun markListEnd(next: MdKind?) {
            val last = paragraphs.lastOrNull() ?: return
            val md = last.md ?: return
            if (md.kind == MdKind.LIST_ITEM && next != MdKind.LIST_ITEM && !md.groupEnd) {
                paragraphs[paragraphs.lastIndex] = last.copy(md = md.copy(groupEnd = true))
            }
        }
    }

    private val BULLETS = listOf("•", "◦", "▪")

    private fun isCjk(c: Char) = c.code >= 0x2E80

    private fun displayWidth(s: String): Int {
        var w = 0
        for (ch in s) w += if (isCjk(ch)) 2 else 1
        return w
    }

    private fun expandLeadingTabs(line: String): String {
        var n = 0
        while (n < line.length && (line[n] == '\t' || line[n] == ' ')) n++
        if (n == 0 || !line.substring(0, n).contains('\t')) return line
        return line.substring(0, n).replace("\t", "    ") + line.substring(n)
    }

    /** 去掉行首的块引用标记，返回（引用层数, 剩余内容；保留列表缩进） */
    private fun stripQuote(line: String): Pair<Int, String> {
        var s = line
        var depth = 0
        while (true) {
            val trimmed = s.trimStart()
            if (!trimmed.startsWith(">") || s.length - trimmed.length > 3) break
            depth++
            s = trimmed.removePrefix(">").removePrefix(" ")
        }
        return depth to s
    }

    private fun String.removeIndent(n: Int): String {
        var k = 0
        while (k < n && k < length && this[k] == ' ') k++
        return substring(k)
    }

    /** 表格行拆分：忽略转义 \| 与行内代码里的 |；去掉首尾空管道 */
    private fun splitRow(row: String): List<String> {
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        var k = 0
        while (k < row.length) {
            val c = row[k]
            when {
                c == '\\' && k + 1 < row.length && row[k + 1] == '|' -> { cur.append('|'); k++ }
                c == '`' -> { inCode = !inCode; cur.append(c) }
                c == '|' && !inCode -> { cells += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            k++
        }
        cells += cur.toString()
        if (row.trimStart().startsWith("|") && cells.isNotEmpty()) cells.removeAt(0)
        if (cells.isNotEmpty() && cells.last().isBlank() && row.trimEnd().endsWith("|")) cells.removeAt(cells.lastIndex)
        return cells
    }
}

// ----------------------------------------------------------------------
// 行内
// ----------------------------------------------------------------------

internal object MdInline {

    class Result(val text: String, val spans: List<MdSpan>)

    fun parse(src: String): Result {
        val out = StringBuilder()
        val spans = ArrayList<MdSpan>()
        parseRange(src, 0, src.length, out, spans)
        return Result(out.toString(), spans)
    }

    private const val PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "copy" to "©", "reg" to "®", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”", "middot" to "·",
    )

    private fun parseRange(s: String, from: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>) {
        var i = from
        while (i < to) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < to && s[i + 1] in PUNCT -> { out.append(s[i + 1]); i += 2 }
                c == '`' -> i = code(s, i, to, out, spans)
                c == '!' && i + 1 < to && s[i + 1] == '[' -> i = image(s, i, to, out, spans)
                c == '[' -> i = link(s, i, to, out, spans)
                c == '<' -> i = angle(s, i, to, out, spans)
                c == '&' -> i = entity(s, i, to, out)
                c == '*' || c == '_' || c == '~' -> i = emphasis(s, i, from, to, out, spans)
                (c == 'h' || c == 'H') && bareUrlAt(s, i, from, to) -> i = bareUrl(s, i, to, out, spans)
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun code(s: String, i: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
        var n = 0
        while (i + n < to && s[i + n] == '`') n++
        var j = i + n
        while (j < to) {
            val k = s.indexOf('`', j)
            if (k < 0 || k >= to) break
            var m = 0
            while (k + m < to && s[k + m] == '`') m++
            if (m == n) {
                var body = s.substring(i + n, k).replace('\n', ' ')
                if (body.length >= 2 && body.startsWith(" ") && body.endsWith(" ") && body.isNotBlank()) {
                    body = body.substring(1, body.length - 1)
                }
                val start = out.length
                out.append(body)
                if (body.isNotEmpty()) spans += MdSpan(MdSpanType.CODE, start, out.length)
                return k + m
            }
            j = k + m
        }
        repeat(n) { out.append('`') }
        return i + n
    }

    /** 找与 s[open]=='[' 配对的 ']'（支持嵌套与转义），找不到返回 -1 */
    private fun matchBracket(s: String, open: Int, to: Int): Int {
        var depth = 0
        var k = open
        while (k < to) {
            when (s[k]) {
                '\\' -> k++
                '`' -> {
                    val close = s.indexOf('`', k + 1)
                    if (close in 0 until to) k = close
                }
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return k }
            }
            k++
        }
        return -1
    }

    private fun matchParen(s: String, open: Int, to: Int): Int {
        var depth = 0
        var k = open
        while (k < to) {
            when (s[k]) {
                '\\' -> k++
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return k }
            }
            k++
        }
        return -1
    }

    private fun image(s: String, i: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
        val close = matchBracket(s, i + 1, to)
        if (close < 0 || close + 1 >= to || s[close + 1] != '(') { out.append('!'); return i + 1 }
        val end = matchParen(s, close + 1, to)
        if (end < 0) { out.append('!'); return i + 1 }
        val alt = s.substring(i + 2, close).replace(Regex("[*_`~]"), "").trim()
        val start = out.length
        out.append(if (alt.isEmpty()) "[图片]" else "[图片: $alt]")
        spans += MdSpan(MdSpanType.IMAGE, start, out.length)
        return end + 1
    }

    private fun link(s: String, i: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
        // 脚注引用 [^id] → [id]
        if (i + 1 < to && s[i + 1] == '^') {
            val k = s.indexOf(']', i)
            if (k in 0 until to) { out.append('[').append(s, i + 2, k).append(']'); return k + 1 }
        }
        val close = matchBracket(s, i, to)
        if (close < 0 || close + 1 >= to) { out.append('['); return i + 1 }
        val next = s[close + 1]
        val end = when (next) {
            '(' -> matchParen(s, close + 1, to)
            '[' -> s.indexOf(']', close + 2).takeIf { it in 0 until to } ?: -1
            else -> -1
        }
        if (end < 0) { out.append('['); return i + 1 }
        val start = out.length
        parseRange(s, i + 1, close, out, spans)
        if (out.length > start) spans += MdSpan(MdSpanType.LINK, start, out.length)
        return end + 1
    }

    private fun angle(s: String, i: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
        if (s.startsWith("<!--", i)) {
            val k = s.indexOf("-->", i + 4)
            return if (k in 0 until to) k + 3 else { out.append('<'); i + 1 }
        }
        val close = s.indexOf('>', i + 1)
        if (close < 0 || close >= to) { out.append('<'); return i + 1 }
        val tag = s.substring(i + 1, close)
        val lower = tag.lowercase()
        if ((lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) &&
            tag.none { it.isWhitespace() }
        ) {
            val start = out.length
            out.append(tag)
            spans += MdSpan(MdSpanType.LINK, start, out.length)
            return close + 1
        }
        val name = Regex("^/?([A-Za-z][A-Za-z0-9-]*)(\\s[^>]*)?/?$").matchEntire(tag)
        if (name == null) { out.append('<'); return i + 1 }
        when (name.groupValues[1].lowercase()) {
            "br" -> out.append('\n')
            "img" -> {
                val alt = Regex("alt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1)?.trim().orEmpty()
                val start = out.length
                out.append(if (alt.isEmpty()) "[图片]" else "[图片: $alt]")
                spans += MdSpan(MdSpanType.IMAGE, start, out.length)
            }
        }
        return close + 1
    }

    private fun entity(s: String, i: Int, to: Int, out: StringBuilder): Int {
        val semi = s.indexOf(';', i + 1)
        if (semi < 0 || semi >= to || semi - i > 10) { out.append('&'); return i + 1 }
        val name = s.substring(i + 1, semi)
        val decoded: String? = when {
            name.startsWith("#x") || name.startsWith("#X") ->
                name.substring(2).toIntOrNull(16)?.let(::codePointString)
            name.startsWith("#") -> name.substring(1).toIntOrNull()?.let(::codePointString)
            else -> ENTITIES[name]
        }
        if (decoded == null) { out.append('&'); return i + 1 }
        out.append(decoded)
        return semi + 1
    }

    private fun codePointString(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null

    private fun bareUrlAt(s: String, i: Int, from: Int, to: Int): Boolean {
        if (!(s.startsWith("http://", i, ignoreCase = true) || s.startsWith("https://", i, ignoreCase = true))) return false
        if (i > from && s[i - 1].isLetterOrDigit()) return false
        return i + 8 < to
    }

    private fun bareUrl(s: String, i: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>): Int {
        var k = i
        while (k < to && !s[k].isWhitespace() && s[k] !in "<>\"" && s[k].code < 0x2E80) k++
        while (k > i && s[k - 1] in ".,;:!?)]'*_~") k--
        if (k - i < 9) { out.append(s[i]); return i + 1 }
        val start = out.length
        out.append(s, i, k)
        spans += MdSpan(MdSpanType.LINK, start, out.length)
        return k
    }

    private fun emphasis(
        s: String, i: Int, from: Int, to: Int, out: StringBuilder, spans: MutableList<MdSpan>,
    ): Int {
        val c = s[i]
        var n = 0
        while (i + n < to && s[i + n] == c) n++
        val prev = if (i > 0) s[i - 1] else ' '
        val after = if (i + n < to) s[i + n] else ' '

        if (c == '~') {
            if (n == 2 && !after.isWhitespace()) {
                val close = findClose(s, i + 2, to, c, 2)
                if (close >= 0) {
                    val start = out.length
                    parseRange(s, i + 2, close, out, spans)
                    if (out.length > start) spans += MdSpan(MdSpanType.STRIKE, start, out.length)
                    return close + 2
                }
            }
            repeat(n) { out.append(c) }
            return i + n
        }

        val canOpen = !after.isWhitespace() && (c == '*' || !prev.isLetterOrDigit())
        if (canOpen) {
            for (k in intArrayOf(minOf(n, 3), 2, 1)) {
                if (k > n) continue
                val close = findClose(s, i + k, to, c, k)
                if (close < 0) continue
                val start = out.length
                parseRange(s, i + k, close, out, spans)
                if (out.length > start) {
                    if (k == 2 || k == 3) spans += MdSpan(MdSpanType.BOLD, start, out.length)
                    if (k == 1 || k == 3) spans += MdSpan(MdSpanType.ITALIC, start, out.length)
                }
                return close + k
            }
        }
        repeat(n) { out.append(c) }
        return i + n
    }

    /** 从 [start] 起找与分隔符 [c]×[k] 配对的收尾位置（收尾分隔符的起点），找不到 -1 */
    private fun findClose(s: String, start: Int, to: Int, c: Char, k: Int): Int {
        var j = start
        while (j < to) {
            val ch = s[j]
            if (ch == '\\') { j += 2; continue }
            if (ch == '`') {
                var m = 0
                while (j + m < to && s[j + m] == '`') m++
                val close = findBacktickRun(s, j + m, to, m)
                j = if (close >= 0) close + m else j + m
                continue
            }
            if (ch == c) {
                var m = 0
                while (j + m < to && s[j + m] == c) m++
                val before = s[j - 1]
                val closeOk = j > start && !before.isWhitespace() &&
                    (c != '_' || j + m >= to || !s[j + m].isLetterOrDigit() || m > k)
                val hit = when (k) {
                    1 -> m == 1
                    2 -> m >= 2
                    else -> m >= 3
                }
                if (hit && closeOk) return if (k == 2) j + (m - 2) else j
                j += m
                continue
            }
            j++
        }
        return -1
    }

    private fun findBacktickRun(s: String, from: Int, to: Int, n: Int): Int {
        var j = from
        while (j < to) {
            val k = s.indexOf('`', j)
            if (k < 0 || k >= to) return -1
            var m = 0
            while (k + m < to && s[k + m] == '`') m++
            if (m == n) return k
            j = k + m
        }
        return -1
    }
}
