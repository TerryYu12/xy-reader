package com.xyreader.archive

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.util.DisplayMetrics
import android.util.LruCache
import android.util.TypedValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.xyreader.core.BookEntity
import com.xyreader.core.Chapter
import com.xyreader.core.NovelFontFamily
import com.xyreader.core.NovelFontWeight
import com.xyreader.data.AppGraph
import com.xyreader.data.GoogleDriveClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request

/**
 * 文字页样式：字色/字号/页面尺寸由打开方（阅读器）传入。
 * 字色按阅读背景映射（如黑底浅字、白底黑字），页面底色不在此——渲染用透明底 ImageBitmap，
 * pager 背景直接透出。
 */
data class NovelStyle(
    /** 正文字色（由阅读器按 ReadBackground 四档映射） */
    val textColorArgb: Int,
    /** 正文字号（NovelFontSize.sp） */
    val fontSizeSp: Float,
    /** 屏幕宽（px） */
    val pageWidthPx: Int,
    /** 屏幕高（px） */
    val pageHeightPx: Int,
    /** 系统字体族（typeface 未解析时的兜底路径用） */
    val fontFamily: NovelFontFamily = NovelFontFamily.SYSTEM_SANS,
    /** 字重；中文系统 fallback 字体也会按该选项加粗 */
    val fontWeight: NovelFontWeight = NovelFontWeight.NORMAL,
    /**
     * 已解析的字体（系统族 / 内置字体 / 导入字体，含字重处理）。
     * 由阅读器 ViewModel 构造；null 时按 [fontFamily] 走系统族兜底。
     */
    val typeface: Typeface? = null,
    /** 行距倍率（旧版固定为 1.5）。 */
    val lineSpacingMultiplier: Float = 1.5f,
    /** 四边页边距，单位 px。 */
    val marginTopPx: Float = 64f,
    val marginBottomPx: Float = 64f,
    val marginLeftPx: Float = 48f,
    val marginRightPx: Float = 48f,
    /** 字符间距，单位 px。 */
    val letterSpacingPx: Float = 0f,
    /**
     * 首行缩进：段落首行缩进 2 个全角字符宽；段落自身已有前导空白时按总宽对齐
     * （缩进量 = 2 字符宽 - 已有空白宽，不叠加）。实现走 LeadingMarginSpan，
     * 不改动文本本身——字符偏移锚点与「复制文字」还原不受影响。
     */
    val firstLineIndent: Boolean = false,
)

/** 一个文字段落：文本 + 所属章节序号（各格式解析器填充） */
data class Paragraph(val text: String, val chapterIndex: Int)

/**
 * 构建带首行缩进的段落（LeadingMarginSpan，仅首行）。
 * AOSP StaticLayout.generate() 对 LeadingMarginSpan 有逐段专门处理（真机生效）；
 * 注意 Robolectric 环境不执行该 span 且会将全角空格渲染为可见块，像素级效果只能真机验证。
 *
 * 缩进量 = 2 全角字符宽 - 段落已有前导空白宽：常见「　　」开头的文本缩进量为 0，
 * 保证总缩进恒为 2 字符、不叠加；全空白段缩进量同样为 0（不挂 span）。
 * 只挂 span、不改文本——getLineStart / 页码字符锚点 / 「复制文字」还原全部保持原偏移。
 */
internal fun buildIndentedParagraph(paint: TextPaint, text: String): CharSequence {
    val span = SpannableString(text)
    var leadEnd = 0
    while (leadEnd < text.length && text[leadEnd].isWhitespace()) leadEnd++
    val leadWidth = if (leadEnd > 0) paint.measureText(text, 0, leadEnd) else 0f
    val indentPx = (paint.measureText("\u3000\u3000") - leadWidth).coerceAtLeast(0f).roundToInt()
    if (indentPx > 0) {
        span.setSpan(
            LeadingMarginSpan.Standard(indentPx, 0),
            0,
            text.length,
            Spanned.SPAN_INCLUSIVE_EXCLUSIVE,
        )
    }
    return span
}

/** 页内片段：第 [paragraphIndex] 段的第 [startLine] 行起共 [lineCount] 行（跨页段落按行切分） */
internal data class PageFragment(
    val paragraphIndex: Int,
    val startLine: Int,
    val lineCount: Int,
)

/** 页面几何：四边内边距（px）和行距倍率由阅读配置提供。 */
internal data class NovelPageMetrics(
    val pageWidthPx: Int,
    val pageHeightPx: Int,
    val paddingLeft: Float = 48f,
    val paddingRight: Float = 48f,
    val paddingTop: Float = 64f,
    val paddingBottom: Float = 64f,
)

/**
 * 文字小说页面源（TXT / EPUB 文本版 / MOBI 文本版）：
 * 解析出的段落序列在构造时用 [StaticLayout] 预分页（跨页段落按行记断点），
 * [renderPage] 把一页的文字画到**透明底** ImageBitmap 上——阅读器 UI 零改动，
 * pager 背景色直接透出，黑底浅字 / 白底黑字由 [NovelStyle.textColorArgb] 映射。
 *
 * == 分页算法（断点结构） ==
 * - 行高 lineHeightPx 取自探针 StaticLayout 相邻内侧行的 getLineTop 差
 *   （同一 TextPaint 下全书行高一致，含配置的行距倍率）；
 * - 页容量 = (pageHeight - paddingTop - paddingBottom) / lineHeightPx（向下取整）；
 * - 逐段构建 StaticLayout 记录行数，行数按页容量切分为若干
 *   [PageFragment]（paragraphIndex, startLine, lineCount），跨页段落被切成
 *   多个片段分属相邻页；页 = 有序片段列表，故 pageCount 在打开时固化。
 *
 * == 渲染 ==
 * 每页新建透明底 ARGB_8888 Bitmap → Canvas 平移 (paddingLeft, 首行槽位 y -
 * layout.getLineTop(startLine)) → clipRect 页内容区 → StaticLayout.draw。
 * 片段定位使用 layout 自身的 getLineTop（行高与分页探针同源），槽位与绘制零漂移。
 *
 * 线程安全：renderPage 用 [Mutex] 串行化（StaticLayout LruCache 与位图生成都在其内），
 * 阻塞工作在 Dispatchers.IO；分页在构造完成（调用方 ArchiveFactory.open 已在 IO 线程）。
 * close 幂等：文本全部在内存，仅清空排版缓存并置关闭标志。
 */
class NovelPageSource internal constructor(
    private val paragraphs: List<Paragraph>,
    chapterMarks: List<ChapterMark>,
    private val style: NovelStyle,
    displayMetrics: DisplayMetrics,
) : AbstractPageSource() {

    private val metrics = NovelPageMetrics(
        pageWidthPx = style.pageWidthPx.coerceAtLeast(1),
        pageHeightPx = style.pageHeightPx.coerceAtLeast(1),
        paddingLeft = style.marginLeftPx.coerceIn(0f, (style.pageWidthPx.coerceAtLeast(1) - 1) / 2f),
        paddingRight = style.marginRightPx.coerceIn(0f, (style.pageWidthPx.coerceAtLeast(1) - 1) / 2f),
        paddingTop = style.marginTopPx.coerceIn(0f, (style.pageHeightPx.coerceAtLeast(1) - 1) / 2f),
        paddingBottom = style.marginBottomPx.coerceIn(0f, (style.pageHeightPx.coerceAtLeast(1) - 1) / 2f),
    )

    /** 正文字画笔：字色由样式决定，字号 sp → px */
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        // TextPaint 自带 style 属性（Paint.Style），必须用限定符引用外层构造参数
        color = this@NovelPageSource.style.textColorArgb
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, this@NovelPageSource.style.fontSizeSp, displayMetrics,
        )
        // 字体由 ViewModel 解析好（系统族/内置/导入 + 字重）直接带在 style 上；
        // 兜底路径（typeface == null）仍按系统族名现算，保证任何调用方不崩。
        typeface = this@NovelPageSource.style.typeface ?: Typeface.create(
            when (this@NovelPageSource.style.fontFamily) {
                NovelFontFamily.SYSTEM_SANS -> Typeface.SANS_SERIF
                NovelFontFamily.SYSTEM_SERIF -> Typeface.SERIF
                NovelFontFamily.SYSTEM_MONOSPACE -> Typeface.MONOSPACE
                else -> Typeface.SANS_SERIF
            },
            if (this@NovelPageSource.style.fontWeight == NovelFontWeight.BOLD) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            },
        )
        letterSpacing = this@NovelPageSource.style.letterSpacingPx
            .coerceIn(-4f, 12f) / textSize.coerceAtLeast(1f)
    }

    /** 内容区宽度（页宽 - 左右内边距），StaticLayout 测量与绘制共用 */
    private val contentWidth =
        (metrics.pageWidthPx - metrics.paddingLeft - metrics.paddingRight).toInt().coerceAtLeast(1)

    /** 统一行高（px）：探针布局的相邻内侧行顶差，分页与渲染共用同一数值保证零漂移 */
    private val lineHeightPx: Int = measureLineHeight()

    /** 页容量行数：页高 - 上下内边距，向下取整；极端小屏至少 1 行 */
    private val linesPerPage: Int =
        (((metrics.pageHeightPx - metrics.paddingTop - metrics.paddingBottom) / lineHeightPx).toInt())
            .coerceAtLeast(1)

    /** 分页结果：每页 = 有序片段列表（跨页段落被按行切开） */
    private val pages: List<List<PageFragment>>

    /** 每页首个文本片段在全书归一化文本中的偏移，用于样式重排后恢复邻近位置。 */
    private val pageStartOffsets: LongArray

    /** 段落在归一化全书文本中的起始偏移；段落之间以一个换行符分隔。 */
    private val paragraphStartOffsets: LongArray = LongArray(paragraphs.size).also { starts ->
        var offset = 0L
        paragraphs.forEachIndexed { index, paragraph ->
            starts[index] = offset
            offset += paragraph.text.length + 1L
        }
    }

    /** 每段首行所在页序（章节标记 → 页区间的映射基础） */
    private val firstLinePage: IntArray

    /** 目录：章节标记映射为页区间（相邻章共享同一页时区间重叠，跳转取 startPage） */
    override val chapters: List<Chapter>

    /** 段落 StaticLayout 缓存：字体/字号固定，key = 段序号，渲染时复用 */
    private val layoutCache = LruCache<Int, StaticLayout>(LAYOUT_CACHE_SIZE)

    /** 串行化渲染（LruCache get-or-build 与位图生成） */
    private val renderMutex = Mutex()

    override val cachedPageCount: Int get() = pages.size

    init {
        val (paged, firstPages) = paginate()
        pages = paged
        pageStartOffsets = LongArray(pages.size)
        var lastParagraph = -1
        var lastLayout: StaticLayout? = null
        pages.forEachIndexed { pageIndex, fragments ->
            val first = fragments.first()
            if (first.paragraphIndex != lastParagraph) {
                lastParagraph = first.paragraphIndex
                lastLayout = buildLayout(paragraphs[lastParagraph].text)
            }
            pageStartOffsets[pageIndex] = paragraphStartOffsets[first.paragraphIndex] +
                lastLayout!!.getLineStart(first.startLine).toLong()
        }
        firstLinePage = firstPages
        chapters = buildChapters(chapterMarks)
    }

    /** 返回目标页首个文本片段的全书字符偏移，适合保存重排锚点。 */
    fun pageStartCharOffset(page: Int): Long {
        require(page in pages.indices) { "页码越界: $page / ${pages.size}" }
        return pageStartOffsets[page]
    }

    /** 根据之前保存的字符偏移定位新排版中的邻近页。 */
    fun pageForCharOffset(offset: Long): Int {
        if (pageStartOffsets.isEmpty()) return 0
        val target = offset.coerceAtLeast(0L)
        var low = 0
        var high = pageStartOffsets.lastIndex
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageStartOffsets[mid] <= target) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    // ---------- 分页 ----------

    /**
     * 预分页：逐段构建 StaticLayout 统计行数，按页容量行数切出片段。
     * 返回（页表, 每段首行所在页序）。
     */
    private fun paginate(): Pair<List<List<PageFragment>>, IntArray> {
        val pagesOut = mutableListOf<List<PageFragment>>()
        var currentPage = mutableListOf<PageFragment>()
        var usedLines = 0
        val firstPages = IntArray(paragraphs.size)

        fun flushPage() {
            if (currentPage.isNotEmpty()) pagesOut += currentPage
            currentPage = mutableListOf()
            usedLines = 0
        }

        paragraphs.forEachIndexed { paraIdx, paragraph ->
            val layout = buildLayout(paragraph.text)
            val lineCount = layout.lineCount
            var startLine = 0
            var firstFragment = true
            while (startLine < lineCount) {
                if (usedLines == linesPerPage) flushPage()
                val take = minOf(lineCount - startLine, linesPerPage - usedLines)
                currentPage += PageFragment(paraIdx, startLine, take)
                if (firstFragment) {
                    firstPages[paraIdx] = pagesOut.size
                    firstFragment = false
                }
                usedLines += take
                startLine += take
            }
        }
        flushPage()
        return pagesOut to firstPages
    }

    /** 统一 StaticLayout 构建：可调行距、含字体 padding、左对齐；首行缩进按样式生效 */
    private fun buildLayout(text: String): StaticLayout {
        val content: CharSequence =
            if (style.firstLineIndent && text.isNotEmpty()) {
                buildIndentedParagraph(textPaint, text)
            } else {
                text
            }
        return StaticLayout.Builder.obtain(content, 0, content.length, textPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, style.lineSpacingMultiplier.coerceIn(0.8f, 2.5f))
            .setIncludePad(true)
            .build()
    }

    /**
     * 行高测量：三行探针布局取相邻内侧行的 getLineTop 差（首行含字体顶部 padding、
     * 与内侧行不等高，故取第 2→3 行差作为统一行高）；探针异常时按字体度量兜底。
     */
    private fun measureLineHeight(): Int {
        val probe = buildLayout("测试\n测\n测试")
        if (probe.lineCount >= 3) {
            val step = probe.getLineTop(2) - probe.getLineTop(1)
            if (step > 0) return step
        }
        val fm = textPaint.fontMetrics
        return ((fm.descent - fm.ascent) * style.lineSpacingMultiplier.coerceIn(0.8f, 2.5f))
            .roundToInt().coerceAtLeast(1)
    }

    // ---------- 目录 ----------

    /** 章节标记 → core.Chapter：startPage = 章首段落首行所在页，endPage = 下一章起始页 - 1 */
    private fun buildChapters(marks: List<ChapterMark>): List<Chapter> {
        if (marks.isEmpty() || pages.isEmpty()) return emptyList()
        val lastPage = pages.lastIndex
        fun pageOf(paragraphIndex: Int): Int =
            firstLinePage[paragraphIndex.coerceIn(0, firstLinePage.lastIndex)]
        return marks.mapIndexed { i, mark ->
            val start = pageOf(mark.paragraphIndex)
            val end = if (i == marks.lastIndex) {
                lastPage
            } else {
                (pageOf(marks[i + 1].paragraphIndex) - 1).coerceAtLeast(start)
            }
            Chapter(
                title = mark.title.ifBlank { "第 ${i + 1} 节" },
                startPage = start,
                endPageInclusive = end.coerceAtMost(lastPage),
            )
        }
    }

    // ---------- 渲染 ----------

    override suspend fun renderPage(index: Int): ImageBitmap {
        checkPage(index)
        return renderMutex.withLock {
            withContext(Dispatchers.IO) {
                val fragments = pages[index]
                // 透明底：不填色，pager 背景直接透出
                val bitmap = Bitmap.createBitmap(
                    metrics.pageWidthPx, metrics.pageHeightPx, Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bitmap)
                var slot = 0
                for (frag in fragments) {
                    val layout = layoutFor(frag.paragraphIndex)
                    val top = metrics.paddingTop + slot * lineHeightPx
                    val bottom = metrics.paddingTop + (slot + frag.lineCount) * lineHeightPx
                    canvas.save()
                    canvas.clipRect(
                        metrics.paddingLeft, top,
                        metrics.pageWidthPx - metrics.paddingRight, bottom,
                    )
                    // 定位：把该片段首行在 layout 中的行顶平移到本页槽位（行高同源，无漂移）
                    canvas.translate(
                        metrics.paddingLeft,
                        top - layout.getLineTop(frag.startLine),
                    )
                    layout.draw(canvas)
                    canvas.restore()
                    slot += frag.lineCount
                }
                bitmap.asImageBitmap()
            }
        }
    }

    /** 取段落 StaticLayout：缓存未命中则构建并入缓存（调用方已持 renderMutex） */
    private fun layoutFor(paragraphIndex: Int): StaticLayout {
        layoutCache.get(paragraphIndex)?.let { return it }
        val layout = buildLayout(paragraphs[paragraphIndex].text)
        layoutCache.put(paragraphIndex, layout)
        return layout
    }

    /**
     * 取某一页的可见文字（「复制文字」用）：按片段行范围从 StaticLayout 还原行内字符，
     * 段间以换行拼接；跨页段落只给出本页部分。与渲染共用 [renderMutex] 串行化。
     */
    suspend fun pageText(page: Int): String {
        checkPage(page)
        return renderMutex.withLock {
            withContext(Dispatchers.IO) {
                pages[page].joinToString("\n") { frag ->
                    val text = paragraphs[frag.paragraphIndex].text
                    val layout = layoutFor(frag.paragraphIndex)
                    val lastLine = (frag.startLine + frag.lineCount - 1)
                        .coerceIn(0, layout.lineCount - 1)
                    val start = layout.getLineStart(frag.startLine).coerceIn(0, text.length)
                    val end = layout.getLineEnd(lastLine).coerceIn(start, text.length)
                    text.substring(start, end).trimEnd()
                }
            }
        }
    }

    /**
     * 封面：文字页是透明底，直接作封面在书架上会"全透明"——把第 0 页合成到
     * 不透明深色底上（封面生成链路固定走默认样式=黑底浅字，深底与字色匹配）。
     */
    override suspend fun renderCover(): ImageBitmap {
        val page = renderPage(0)
        return withContext(Dispatchers.IO) {
            val src = page.asAndroidBitmap()
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            canvas.drawColor(COVER_BACKGROUND)
            canvas.drawBitmap(src, 0f, 0f, null)
            src.recycle()
            out.asImageBitmap()
        }
    }

    /** 无底层资源（文本已在内存）：清空排版缓存并置关闭标志（checkPage 此后拒绝渲染） */
    override fun close() = onFirstClose {
        layoutCache.evictAll()
    }

    companion object {

        /** 封面合成底色（近黑，与默认黑底浅字样式匹配） */
        private val COVER_BACKGROUND = 0xFF101010.toInt()

        /** 段落 StaticLayout 缓存容量 */
        private const val LAYOUT_CACHE_SIZE = 200

        /** TXT 大小防御上限：20MB */
        private const val MAX_TXT_BYTES = 20L * 1024L * 1024L

        /** 默认正文字色：黑底浅字（style == null 时） */
        private val DEFAULT_TEXT_COLOR = 0xFFCCCCCC.toInt()

        /** 默认字号（NovelFontSize.MEDIUM） */
        private const val DEFAULT_FONT_SIZE_SP = 19f

        /** 远程文本缓存目录清理阈值（与图片远程缓存共用 remote_cache 目录） */
        private const val TEXT_CACHE_TRIM_THRESHOLD = 1L shl 30
        private const val TEXT_CACHE_TARGET = 800L * 1024L * 1024L

        /**
         * 由已解析的段落/章节标记构造（EPUB / MOBI 文本管线经 ArchiveFactory 调用）。
         * style == null 时按 displayMetrics 现算页面尺寸并使用默认样式（黑底浅字 19f）。
         */
        fun open(
            context: Context,
            paragraphs: List<Paragraph>,
            marks: List<ChapterMark>,
            style: NovelStyle?,
        ): NovelPageSource {
            if (paragraphs.isEmpty()) throw IOException("未解析到文本内容")
            return NovelPageSource(
                paragraphs, marks, resolveStyle(context, style), context.resources.displayMetrics,
            )
        }

        /**
         * 打开本地 TXT（book.uri = file://、/ 开头路径或 content:// SAF 文档）：
         * 全文读入内存（>20MB 抛"文件过大"）→ 编码探测 → 章节切分 → 分页。
         */
        fun fromFile(context: Context, book: BookEntity, style: NovelStyle?): NovelPageSource {
            val bytes = readBookBytes(context, book)
            val text = NovelTextExtractor.decodeTxt(bytes)
            val (paragraphs, marks) = NovelTextExtractor.txtToBook(text)
            return open(context, paragraphs, marks, style)
        }

        /** 打开 WebDAV 远程 TXT：整文下载到 cacheDir/remote_cache 后走本地实现 */
        fun fromWebDavText(
            context: Context,
            book: BookEntity,
            url: String,
            username: String?,
            password: String?,
            style: NovelStyle?,
        ): NovelPageSource {
            val file = ensureRemoteTextFile(context, book, url) { builder ->
                if (!username.isNullOrEmpty()) {
                    // 密码只进请求头，绝不落日志
                    builder.header("Authorization", Credentials.basic(username, password ?: ""))
                }
            }
            val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
            return fromFile(context, localBook, style)
        }

        /** 打开 Google Drive 远程 TXT：动态 Bearer 鉴权整文下载到缓存后走本地实现 */
        fun fromGdriveText(
            context: Context,
            book: BookEntity,
            fileId: String,
            accountId: Long,
            style: NovelStyle?,
        ): NovelPageSource {
            val file = ensureRemoteTextFile(context, book, GoogleDriveClient.downloadUrl(fileId)) { builder ->
                // 动态 Bearer token：调用点在 IO 线程（ArchiveFactory.open 契约），runBlocking 安全；
                // token 只进请求头，绝不落日志
                builder.header(
                    "Authorization",
                    "Bearer " + runBlocking {
                        AppGraph.libraryRepository(context).refreshGdriveAccessToken(accountId)
                    },
                )
            }
            val localBook = book.copy(uri = "file://" + file.absolutePath, size = file.length())
            return fromFile(context, localBook, style)
        }

        /** style == null 时的默认样式：黑底浅字 + 19f + 当前屏幕尺寸（比固定 1080x2000 更准） */
        private fun resolveStyle(context: Context, style: NovelStyle?): NovelStyle {
            if (style != null) return style
            val dm = context.resources.displayMetrics
            return NovelStyle(
                textColorArgb = DEFAULT_TEXT_COLOR,
                fontSizeSp = DEFAULT_FONT_SIZE_SP,
                pageWidthPx = dm.widthPixels.coerceAtLeast(1),
                pageHeightPx = dm.heightPixels.coerceAtLeast(1),
            )
        }

        /** 读取 TXT 全字节：本地文件先按长度拒绝超限；content:// 用封顶读取防内存失控 */
        private fun readBookBytes(context: Context, book: BookEntity): ByteArray {
            val local = PageSources.resolveLocalFile(book)
            if (local != null) {
                if (local.length() > MAX_TXT_BYTES) throw IOException("文件过大（TXT 上限 20MB）")
                return local.readBytes()
            }
            if (book.uri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(book.uri))?.use { input ->
                    return readBytesCapped(input, MAX_TXT_BYTES)
                } ?: throw IOException("无法打开 TXT 文档: ${book.uri}")
            }
            throw IllegalArgumentException("无法识别的 TXT 文件 URI: ${book.uri}")
        }

        /** 封顶读取：超过 cap 即抛"文件过大"（content:// 无法先验长度时的防御） */
        private fun readBytesCapped(input: InputStream, cap: Long): ByteArray {
            val out = ByteArrayOutputStream(minOf(cap, 1L shl 20).toInt().coerceAtLeast(8192))
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > cap) throw IOException("文件过大（TXT 上限 20MB）")
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }

        /**
         * 确保远程 TXT 已下载到 cacheDir/remote_cache/{uri 的 SHA-1}.txt
         * （与图片远程缓存共用目录；下载模式照 RemoteArchiveSources.ensureRemoteFile：
         * .part 先写再 rename，不落半截正式文件）。
         */
        private fun ensureRemoteTextFile(
            context: Context,
            book: BookEntity,
            url: String,
            authSetup: (Request.Builder) -> Unit,
        ): File {
            val dir = File(context.cacheDir, "remote_cache").apply { mkdirs() }
            val target = File(dir, "${sha1Hex(book.uri)}.txt")
            if (target.exists() && target.length() > 0) return target

            trimTextCache(dir, keep = target)

            val tmp = File(dir, "${target.name}.part")
            try {
                val builder = Request.Builder().url(url)
                authSetup(builder)
                val response = try {
                    HttpRangeChannel.defaultHttpClient().newCall(builder.build()).execute()
                } catch (e: IOException) {
                    throw IOException("远程 TXT 下载失败（网络错误）: ${e.message}", e)
                }
                response.use { resp ->
                    if (resp.code == 401 || resp.code == 403) {
                        throw IOException("HTTP ${resp.code}: 远程 TXT 下载失败（认证失败，请检查远程仓库凭据）")
                    }
                    if (!resp.isSuccessful) {
                        throw IOException("HTTP ${resp.code}: 远程 TXT 下载失败")
                    }
                    val body = resp.body ?: throw IOException("HTTP ${resp.code}: 响应无内容")
                    tmp.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                }
                if (tmp.length() <= 0L) {
                    throw IOException("远程 TXT 下载失败：响应成功但内容为 0 字节")
                }
                if (tmp.length() > MAX_TXT_BYTES) throw IOException("文件过大（TXT 上限 20MB）")
                if (!tmp.renameTo(target)) {
                    // rename 失败（极端并发下目标可能已被创建）：目标有效则用目标，否则重试 rename
                    if (target.exists() && target.length() > 0) {
                        tmp.delete()
                    } else {
                        check(tmp.renameTo(target)) { "缓存文件重命名失败: ${tmp.name}" }
                    }
                }
                return target
            } catch (t: Throwable) {
                tmp.delete()
                throw t
            }
        }

        /** remote_cache 总量超过 1GB 时按最后修改时间删最旧文件，直到 <800MB（本次目标文件不动） */
        private fun trimTextCache(dir: File, keep: File) {
            val files = dir.listFiles()?.filter { it.isFile } ?: return
            var total = files.sumOf { it.length() }
            if (total <= TEXT_CACHE_TRIM_THRESHOLD) return
            for (f in files.sortedBy { it.lastModified() }) {
                if (total <= TEXT_CACHE_TARGET) break
                if (f == keep) continue
                val len = f.length()
                if (f.delete()) total -= len
            }
        }

        /** URI 的 SHA-1 十六进制：缓存文件名用，规避特殊字符与长度问题 */
        private fun sha1Hex(s: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
