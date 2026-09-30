package com.xyreader.archive

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * 章节标记：章首所在的全局段落序号（由 [NovelPageSource] 分页后映射为页区间）。
 * 与 core.Chapter 解耦：core.Chapter 需要"页区间"，只有分页后才能算出。
 */
data class ChapterMark(val title: String, val paragraphIndex: Int)

/**
 * EPUB 判定为"图片版"的回退哨兵：由 [NovelTextExtractor.parseEpub] 抛出，
 * ArchiveFactory 捕获后回退到 ZipPageSource 图片管线。正常不会泄漏到 UI。
 */
class EpubImageBasedException(message: String) : IOException(message)

/**
 * MOBI/AZW3 判定为"非文本管线"（图片版 / 压缩或加密不支持 / 无文本）的回退哨兵：
 * 由探测函数抛出，ArchiveFactory 捕获后回退到现有 MobiPageSource 图片管线。
 */
internal class MobiNotTextException(message: String = "该 MOBI/AZW3 为图片版或暂不支持，转图片管线") :
    IOException(message)

/** EPUB 文本版解析结果：段落序列（chapterIndex = spine 文本文档序号）+ 每文档一个章节标记 + 可选书内封面 */
class EpubTextData(
    val paragraphs: List<Paragraph>,
    val marks: List<ChapterMark>,
    /** 书内封面图字节（meta cover → EPUB3 cover-image → 文件名兜底；无则 null）——由封面渲染使用 */
    val coverBytes: ByteArray? = null,
)

/**
 * 文字小说解析器（TXT / EPUB 文本版 / MOBI 文本版）：
 * 全部输出 [Paragraph] 列表 + [ChapterMark] 章节标记，交给 [NovelPageSource] 分页渲染。
 *
 * - 不引任何第三方依赖：EPUB 的 OPF 用 Android 自带 XmlPullParser 解析，
 *   XHTML/HTML 清洗用正则 + 手写实体解码表；
 * - MOBI 复刻 [MobiPageSource] 的 PDB 容器解析思路（不修改原文件），
 *   PalmDOC LZ77 解压为本文件独立实现；
 * - 判定"文本版 vs 图片版"的探测函数（[parseEpub] 快速失败 / [probeMobi]）供 ArchiveFactory 路由。
 */
object NovelTextExtractor {

    // ==================================================================
    // TXT
    // ==================================================================

    /** TXT 章首行正则：行首可有空白，"第X章/节/回/卷/部/集"（中文数字或阿拉伯数字）、Chapter N、固定楔子词 */
    private val TXT_CHAPTER_REGEX = Regex(
        "^[ \\t]*(第[0-9零一二三四五六七八九十百千万两]+[章节回卷部集]|Chapter\\s+\\d+|序章|楔子|前言|后记|尾声).*[ \\t]*$",
        RegexOption.MULTILINE,
    )

    /** TXT 编码探测 → 全文解码：
     * BOM（EF BB BF / FF FE / FE FF）直接判定；否则尝试 UTF-8 严格解码（REPORT，
     * 出现任何非法序列即失败），失败回退 GBK（中文 TXT 的最常见兜底编码）。 */
    fun decodeTxt(bytes: ByteArray): String {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("GBK"))
        }
    }

    /**
     * TXT 全文 → 段落 + 章节标记：
     * 按行扫描，命中 [TXT_CHAPTER_REGEX] 的行作为章首（单独成段，chapterIndex 指向新章）；
     * 其余非空行各成一段；空白行丢弃。全书无任何章首 → 单章（标题"正文"）。
     * 文档在首个章首之前出现的内容归入隐式第 0 章（同样标题"正文"）。
     */
    fun txtToBook(text: String): Pair<List<Paragraph>, List<ChapterMark>> {
        val paragraphs = mutableListOf<Paragraph>()
        val marks = mutableListOf<ChapterMark>()
        var currentChapter = -1
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            if (TXT_CHAPTER_REGEX.containsMatchIn(raw)) {
                marks += ChapterMark(title = line, paragraphIndex = paragraphs.size)
                currentChapter = marks.size - 1
            } else if (currentChapter < 0) {
                // 章首之前先出现了正文：补一个隐式"正文"章
                marks += ChapterMark(title = "正文", paragraphIndex = paragraphs.size)
                currentChapter = marks.size - 1
            }
            paragraphs += Paragraph(text = line, chapterIndex = currentChapter)
        }
        if (marks.isEmpty()) marks += ChapterMark(title = "正文", paragraphIndex = 0)
        return paragraphs to marks
    }

    // ==================================================================
    // EPUB 文本版
    // ==================================================================

    /** EPUB 条目数防御上限（解压 bomb）：超过即拒绝打开 */
    private const val EPUB_MAX_ENTRIES = 2000

    /** EPUB spine 认可的文本 media-type（部分老 EPUB media-type 缺失时按扩展名兜底） */
    private val EPUB_TEXT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")

    /** 封面图扩展名兜底集（media-type 缺失的老书按扩展名识别） */
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    /** 封面图字节上限（防个别书用超大图当解析炸弹；正常封面远小于此） */
    private const val MAX_COVER_BYTES = 8 * 1024 * 1024

    /** `<img` 探测（忽略大小写，限定制约字节避免误匹配 `<input` 等） */
    private val IMG_TAG_REGEX = Regex("(?i)<img[\\s/>]")

    /** 首个文本条目含 <img> / spine 全无文本条目 → 图片版 EPUB，路由层回退图片管线 */
    fun parseEpub(zip: ZipFile): EpubTextData {
        // 1. 条目索引（bomb 防御：条目数超限直接拒绝）
        val entryMap = HashMap<String, ZipArchiveEntry>()
        var count = 0
        val entryIter = zip.entries
        while (entryIter.hasMoreElements()) {
            val entry = entryIter.nextElement()
            count++
            if (count > EPUB_MAX_ENTRIES) {
                throw IOException("EPUB 条目数超过 $EPUB_MAX_ENTRIES，疑似解压炸弹，已拒绝打开")
            }
            if (!entry.isDirectory) entryMap[entry.name] = entry
        }

        // 2. META-INF/container.xml → OPF 路径（正则取 full-path，兼容单双引号）
        val containerEntry = entryMap["META-INF/container.xml"]
            ?: throw IOException("EPUB 缺少 META-INF/container.xml")
        val containerXml = readEntryText(zip, containerEntry)
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']")
            .find(containerXml)?.groupValues?.get(1)
            ?: throw IOException("container.xml 中未找到 rootfile full-path")
        val opfEntry = entryMap[opfPath]
            ?: entryMap[urlDecode(opfPath)]
            ?: throw IOException("EPUB 缺少 OPF 文件: $opfPath")

        // 3. OPF：XmlPullParser 解析 manifest（id → href/media-type）与 spine（itemref 顺序）
        val opfXml = readEntryText(zip, opfEntry)
        val parsed = parseOpf(opfXml)
        val opfDir = opfEntry.name.substringBeforeLast('/', "")

        // 4. spine 里的文本条目（按 manifest media-type 判定，缺失时按 href 扩展名兜底）
        val textDocs = parsed.spineRefs.mapNotNull { idref ->
            parsed.manifest[idref]
        }.filter { item ->
            item.mediaType.lowercase(Locale.US) in EPUB_TEXT_MEDIA_TYPES ||
                item.href.substringAfterLast('.', "").lowercase(Locale.US) in setOf("xhtml", "html", "htm")
        }
        if (textDocs.isEmpty()) {
            throw EpubImageBasedException("EPUB spine 中没有任何文本条目（图片版）")
        }

        // 5. 图片版快速判定：首个文本条目含 <img> → 回退图片管线
        val firstEntry = resolveZipEntry(entryMap, textDocs[0].href, opfDir)
            ?: throw EpubImageBasedException("EPUB 首个文本条目缺失")
        val firstHtml = readEntryText(zip, firstEntry)
        if (IMG_TAG_REGEX.containsMatchIn(firstHtml)) {
            throw EpubImageBasedException("EPUB 首个内容文档包含 <img>（图片版）")
        }

        // 6. 逐文档提取文本：每章 = 一个 spine 文档，章标题取该文档 <title>，缺省"第 N 节"。
        //    提取为空（纯图页 / 空白页，如 SVG 包裹的封面页、插图页）的文档不生成空章节。
        val paragraphs = mutableListOf<Paragraph>()
        val marks = mutableListOf<ChapterMark>()
        textDocs.forEachIndexed { docIndex, item ->
            val html = if (docIndex == 0) firstHtml else {
                val entry = resolveZipEntry(entryMap, item.href, opfDir) ?: return@forEachIndexed
                readEntryText(zip, entry)
            }
            val docTexts = htmlToParagraphs(html)
            if (docTexts.isEmpty()) return@forEachIndexed
            marks += ChapterMark(
                title = extractHtmlTitle(html) ?: "第 ${docIndex + 1} 节",
                paragraphIndex = paragraphs.size,
            )
            docTexts.forEach { paragraphs += Paragraph(text = it, chapterIndex = docIndex) }
        }
        if (paragraphs.isEmpty()) {
            throw EpubImageBasedException("EPUB 文本条目均未提取到内容（图片版）")
        }

        // 7. 书内封面图（可选）：meta cover → EPUB3 cover-image → 文件名兜底；
        //    读取失败返回 null 不阻断（封面渲染会回退为文字页合成）
        val coverBytes = resolveCoverEntry(entryMap, parsed, opfDir)
            ?.let { readEntryBytes(zip, it, MAX_COVER_BYTES) }
        return EpubTextData(paragraphs, marks, coverBytes)
    }

    /** OPF 解析结果：manifest 的 id → (href, media-type)，spine 的 idref 顺序 */
    private class OpfData(
        val manifest: MutableMap<String, OpfItem> = mutableMapOf(),
        val spineRefs: MutableList<String> = mutableListOf(),
        /** <meta name="cover" content="...">（EPUB2；content 多为 manifest id，也可能是文件名） */
        var metaCoverContent: String? = null,
    )

    private class OpfItem(val href: String, val mediaType: String, val properties: String = "")

    /** 用 Android 自带 XmlPullParser 解析 OPF（manifest + spine），不引第三方 XML 库 */
    private fun parseOpf(opfXml: String): OpfData {
        val data = OpfData()
        try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(opfXml))
            var inManifest = false
            var inSpine = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name.lowercase(Locale.US)) {
                        "manifest" -> inManifest = true
                        "spine" -> inSpine = true
                        "item" -> if (inManifest) {
                            val id = parser.getAttributeValue(null, "id")
                            val href = parser.getAttributeValue(null, "href")
                            if (!id.isNullOrEmpty() && !href.isNullOrEmpty()) {
                                data.manifest[id] = OpfItem(
                                    href = href,
                                    mediaType = parser.getAttributeValue(null, "media-type").orEmpty(),
                                    properties = parser.getAttributeValue(null, "properties").orEmpty(),
                                )
                            }
                        }
                        "itemref" -> if (inSpine) {
                            parser.getAttributeValue(null, "idref")?.let { data.spineRefs += it }
                        }
                        // EPUB2 惯用封面声明：<meta name="cover" content="manifest-id 或文件名">
                        "meta" -> if (parser.getAttributeValue(null, "name").equals("cover", ignoreCase = true)) {
                            data.metaCoverContent = parser.getAttributeValue(null, "content")
                                ?.trim()?.takeIf { it.isNotEmpty() }
                        }
                    }
                    XmlPullParser.END_TAG -> when (parser.name.lowercase(Locale.US)) {
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            throw IOException("EPUB OPF 解析失败: ${e.message}", e)
        }
        return data
    }

    /** 按相对 OPF 目录解析 href → zip 条目：原始名与 URL 解码名各试一次 */
    private fun resolveZipEntry(
        entryMap: Map<String, ZipArchiveEntry>,
        href: String,
        opfDir: String,
    ): ZipArchiveEntry? {
        val clean = href.substringBefore('#').trim()
        if (clean.isEmpty()) return null
        val joined = if (opfDir.isEmpty()) clean else "$opfDir/$clean"
        return entryMap[joined]
            ?: entryMap[urlDecode(joined)]
            ?: entryMap[urlDecode(clean)] // 个别 EPUB 的 href 相对 zip 根
    }

    /** 封面条目解析：meta cover（id 或文件名）→ EPUB3 cover-image 属性 → 文件名含 cover 的图片 */
    private fun resolveCoverEntry(
        entryMap: Map<String, ZipArchiveEntry>,
        parsed: OpfData,
        opfDir: String,
    ): ZipArchiveEntry? {
        // 1) EPUB2 惯用：<meta name="cover" content="...">——先按 manifest id 查，再按路径/文件名直接解析
        parsed.metaCoverContent?.let { content ->
            val byId = parsed.manifest[content]
            if (byId != null && isImageItem(byId)) {
                resolveZipEntry(entryMap, byId.href, opfDir)?.let { return it }
            }
            resolveZipEntry(entryMap, content, opfDir)?.let { return it }
        }
        // 2) EPUB3：manifest item 的 properties 含 cover-image
        parsed.manifest.values.firstOrNull { it.properties.split(' ').contains("cover-image") }
            ?.let { item -> resolveZipEntry(entryMap, item.href, opfDir)?.let { return it } }
        // 3) 文件名兜底：href 含 cover 且是图片条目
        parsed.manifest.values.firstOrNull { isImageItem(it) && it.href.lowercase(Locale.US).contains("cover") }
            ?.let { item -> resolveZipEntry(entryMap, item.href, opfDir)?.let { return it } }
        return null
    }

    /** 是否图片条目：media-type 以 image/ 开头，或按扩展名兜底 */
    private fun isImageItem(item: OpfItem): Boolean {
        if (item.mediaType.startsWith("image/", ignoreCase = true)) return true
        return item.href.substringAfterLast('.', "").lowercase(Locale.US) in IMAGE_EXTS
    }

    /** 读取 zip 条目字节（封面用）：声明尺寸 + 实际读取量双重限幅，超限/失败返回 null */
    private fun readEntryBytes(zip: ZipFile, entry: ZipArchiveEntry, limit: Int): ByteArray? {
        if (entry.size > limit) return null
        return try {
            zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > limit) return null
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null // 封面可选，读取失败不阻断解析
        }
    }

    // ==================================================================
    // MOBI / AZW3 文本版（PDB 容器解析 + PalmDOC 解压）
    // ==================================================================

    /**
     * MOBI 文本管线的探测结果与解析上下文（字段偏移对照 [MobiPageSource] 的注释表，全大端）：
     *
     * PDB header：numRecords @76..78（2B）；78..78+8n 记录偏移表（offset 4B + attributes 1B + uniqueID 3B）
     * record0（PalmDOC header）：compression @+0（1=无压缩 / 2=PalmDOC / 17480=HUFF/CDIC）；
     *   encryption @+12（0=无 DRM）；textRecordCount @+8（2B）；
     *   MOBI 魔数 @+16；textEncoding @ record0+44（MOBI header +28，1252 或 65001）；
     *   firstImageIndex @ record0+108（MOBI header +92；0xFFFFFFFF = 无图）
     */
    class MobiTextInfo internal constructor(
        internal val offsets: LongArray,
        internal val numRecords: Int,
        internal val fileSize: Long,
        internal val compression: Int,
        internal val encryption: Int,
        internal val textRecordCount: Int,
        internal val firstImageIndex: Long,
        internal val charset: Charset,
    ) {
        /** 无图像标记值 */
        internal val noImageFlag: Long = 0xFFFFFFFFL

        /**
         * 是否走文本管线：压缩可支持（1/2）+ 无加密 + 有文本记录 + 无图片资源
         * （firstImageIndex 无效：0xFFFFFFFF 或越界——粗判，与路由约定一致）。
         * 任一条件不满足 → 抛 [MobiNotTextException]，由路由层回退 MobiPageSource。
         */
        internal fun requireTextCandidate() {
            if (compression != 1 && compression != 2) {
                throw MobiNotTextException("该 MOBI 使用了暂不支持的压缩格式（compression=$compression）")
            }
            if (encryption != 0) {
                throw MobiNotTextException("该 MOBI 有 DRM 加密，无法读取")
            }
            if (textRecordCount <= 0) {
                throw MobiNotTextException("该 MOBI 没有文本记录")
            }
            if (firstImageIndex != noImageFlag && firstImageIndex < numRecords) {
                // 首图索引有效：大概率图片版（漫画），交给现有图片管线
                throw MobiNotTextException()
            }
        }
    }

    /**
     * 探测 MOBI/AZW3 是否可走文本管线：只读 PDB 头 + 偏移表 + record0（远程文件仅几次
     * 小块 Range 读）。结构非法抛 IOException；"结构合法但不适合文本管线"抛 [MobiNotTextException]
     * （由 ArchiveFactory 捕获后回退现有 MobiPageSource，保持其原有错误提示/图片行为）。
     */
    fun probeMobi(channel: SeekableByteChannel): MobiTextInfo {
        val fileSize = channel.size()
        // 1. PDB 头：记录数
        channel.position(0)
        val head = readUpTo(channel, PDB_HEADER_SIZE)
        if (head.size < PDB_HEADER_SIZE) throw IOException("文件过小或损坏")
        val numRecords = readUShortBE(head, 76)
        if (numRecords == 0) throw IOException("文件过小或损坏")

        // 2. 记录偏移表
        val tableEnd = PDB_HEADER_SIZE + PDB_ENTRY_SIZE * numRecords
        if (tableEnd > fileSize) throw IOException("文件过小或损坏")
        channel.position(PDB_HEADER_SIZE.toLong())
        val table = readUpTo(channel, PDB_ENTRY_SIZE * numRecords)
        if (table.size < tableEnd - PDB_HEADER_SIZE) throw IOException("文件过小或损坏")
        val offsets = LongArray(numRecords)
        for (i in 0 until numRecords) offsets[i] = readUIntBE(table, i * PDB_ENTRY_SIZE)

        // 3. record0：压缩/加密/文本记录数
        val rec0Offset = offsets[0]
        if (rec0Offset < tableEnd || rec0Offset >= fileSize) throw IOException("文件过小或损坏")
        val rec0Length = (if (numRecords > 1) offsets[1] else fileSize) - rec0Offset
        if (rec0Length <= 0) throw IOException("文件过小或损坏")
        channel.position(rec0Offset)
        val rec0 = readUpTo(channel, minOf(RECORD0_READ_SIZE.toLong(), rec0Length).toInt())
        if (rec0.size < RECORD0_REQUIRED) throw IOException("不是有效的 MOBI/AZW3 文件")

        val compression = readUShortBE(rec0, 0)
        val textRecordCount = readUShortBE(rec0, 8)
        val encryption = readUShortBE(rec0, 12)

        // 4. 字符集：有 MOBI 头读 textEncoding（1252/65001）；纯 PalmDOC 无 MOBI 头时
        //    用首个文本记录试解 UTF-8，失败回退 windows-1252
        val hasMobiMagic = rec0.size >= MOBI_MAGIC_AT + 4 &&
            readUIntBE(rec0, MOBI_MAGIC_AT) == MOBI_MAGIC
        val charset = if (hasMobiMagic) {
            when (readUIntBE(rec0, TEXT_ENCODING_AT).toInt()) {
                1252 -> Charset.forName("windows-1252")
                else -> Charsets.UTF_8
            }
        } else {
            probeCharset(channel, offsets, numRecords, fileSize, textRecordCount)
        }

        // 5. firstImageIndex：仅当 MOBI 头足够长（headerLength 覆盖该字段）且字节读到时才可信
        val headerLength = if (rec0.size >= MOBI_HEADER_LENGTH_AT + 4) readUIntBE(rec0, MOBI_HEADER_LENGTH_AT) else 0L
        val firstImageIndex = if (hasMobiMagic && headerLength >= FIRST_IMAGE_INDEX_IN_HEADER &&
            rec0.size >= FIRST_IMAGE_INDEX_AT + 4
        ) {
            readUIntBE(rec0, FIRST_IMAGE_INDEX_AT)
        } else {
            0xFFFFFFFFL
        }

        return MobiTextInfo(
            offsets = offsets, numRecords = numRecords, fileSize = fileSize,
            compression = compression, encryption = encryption,
            textRecordCount = textRecordCount, firstImageIndex = firstImageIndex,
            charset = charset,
        )
    }

    /** 纯 PalmDOC（无 MOBI 头）的字符集探测：首文本记录严格 UTF-8 解码成功 → UTF-8，否则 windows-1252 */
    private fun probeCharset(
        channel: SeekableByteChannel,
        offsets: LongArray,
        numRecords: Int,
        fileSize: Long,
        textRecordCount: Int,
    ): Charset {
        if (textRecordCount <= 0 || numRecords < 2) return Charset.forName("windows-1252")
        val offset = offsets[1]
        if (offset <= 0 || offset >= fileSize) return Charset.forName("windows-1252")
        val length = (if (numRecords > 2) offsets[2] else fileSize) - offset
        if (length <= 0) return Charset.forName("windows-1252")
        channel.position(offset)
        val sample = readUpTo(channel, minOf(length, 4096L).toInt())
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample))
            Charsets.UTF_8
        } catch (_: CharacterCodingException) {
            Charset.forName("windows-1252")
        }
    }

    /**
     * 提取 MOBI 全文：record 1..textRecordCount 逐记录解压拼接（record 0 是头部）。
     * 单记录解压上限 1MB（PalmDOC 正常 4096 字节/记录，超限即视为数据损坏防御）。
     * HUFF/CDIC 压缩在 [MobiTextInfo.requireTextCandidate] 已拦截，这里兜底再拒一次。
     */
    fun extractMobiHtml(channel: SeekableByteChannel, info: MobiTextInfo): String {
        if (info.compression == COMPRESSION_HUFF_CDIC) {
            throw IOException("该文件使用了暂不支持的压缩格式（HUFF/CDIC）")
        }
        val recordCount = info.textRecordCount.coerceAtMost(info.numRecords - 1)
        val out = StringBuilder()
        for (r in 1..recordCount) {
            val offset = info.offsets[r]
            if (offset < 0 || offset >= info.fileSize) continue
            val length = ((if (r + 1 < info.numRecords) info.offsets[r + 1] else info.fileSize) - offset)
                .coerceAtMost(MAX_MOBI_RECORD_BYTES.toLong())
            if (length <= 0) continue
            channel.position(offset)
            val record = readUpTo(channel, length.toInt())
            val decompressed = if (info.compression == 2) palmDocDecompress(record) else record
            out.append(String(decompressed, info.charset))
        }
        return out.toString()
    }

    /**
     * PalmDOC LZ77 解压（标准算法，MobileRead wiki 规范）：
     * - 0x00：字面量 NUL；
     * - 0x01..0x08：后随 1..8 个字面量；
     * - 0x09..0x7F：字面量自身；
     * - 0x80..0xBF：与下一字节组成 16 位 token：低 3 位 = 拷贝长度-3（3..10），
     *   高位段 = 回退距离（1..2048），从已输出尾部按字节向前拷贝（距离可小于长度，逐字节
     *   拷贝天然支持重叠区）；
     * - 0xC0..0xFF："空格 + (b and 0x7F)"。
     * 输出不会超过输入长度（所有 token 都不膨胀），预分配输入等长缓冲，防御性超限拒绝。
     */
    internal fun palmDocDecompress(data: ByteArray): ByteArray {
        var out = ByteArray(maxOf(data.size, 64))
        var pos = 0
        var i = 0
        while (i < data.size) {
            val b = data[i++].toInt() and 0xFF
            when {
                b == 0 -> {
                    out = appendByte(out, pos, 0); pos++
                }
                b < 8 -> {
                    // 字面量游程：后随 b 个字节
                    val run = minOf(b, data.size - i)
                    out = ensureCapacity(out, pos + run)
                    System.arraycopy(data, i, out, pos, run)
                    pos += run
                    i += run
                }
                b < 0x80 -> {
                    out = appendByte(out, pos, b); pos++
                }
                b < 0xC0 -> {
                    if (i >= data.size) throw IOException("PalmDOC 解压失败：数据被截断")
                    val b2 = data[i++].toInt() and 0xFF
                    val token = (b shl 8) or b2
                    val length = (token and 0x7) + 3
                    val distance = (token shr 3) and 0x7FF
                    if (distance == 0 || distance > pos) {
                        throw IOException("PalmDOC 解压失败：非法回溯距离")
                    }
                    out = ensureCapacity(out, pos + length)
                    repeat(length) {
                        out[pos] = out[pos - distance]
                        pos++
                    }
                }
                else -> {
                    out = ensureCapacity(out, pos + 2)
                    out[pos++] = ' '.code.toByte()
                    out[pos++] = (b and 0x7F).toByte()
                }
            }
            if (pos > MAX_MOBI_RECORD_BYTES) throw IOException("PalmDOC 解压失败：单记录膨胀超限")
        }
        return out.copyOf(pos)
    }

    /** 追加单字节（必要时扩容）；PalmDOC 输出 ≤ 输入，扩容只在数据损坏时触发 */
    private fun appendByte(buf: ByteArray, pos: Int, value: Int): ByteArray {
        val out = ensureCapacity(buf, pos + 1)
        out[pos] = value.toByte()
        return out
    }

    private fun ensureCapacity(buf: ByteArray, need: Int): ByteArray {
        if (need <= buf.size) return buf
        var newSize = buf.size
        while (newSize < need) newSize *= 2
        return buf.copyOf(newSize)
    }

    // ==================================================================
    // HTML/XHTML → 纯文本段落（EPUB 与 MOBI 共用）
    // ==================================================================

    /** 去掉整块 <head>（EPUB 的 <title>/<meta> 不属于正文；MOBI 片段无 head 时无操作） */
    private val HEAD_REGEX = Regex(
        "<head[^>]*>.*?</head>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 去掉 <style>/<script> 块（含内容），忽略大小写、跨行 */
    private val SCRIPT_STYLE_REGEX = Regex(
        "<(script|style)[^>]*>.*?</\\s*\\1\\s*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** HTML 注释 */
    private val COMMENT_REGEX = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))

    /** <br> → 换行 */
    private val BR_REGEX = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)

    /** 块级标签（开/闭）→ 换行：p / div / h1-6 / li / blockquote / tr / section / article */
    private val BLOCK_TAG_REGEX = Regex(
        "</?(?:p|div|h[1-6]|li|blockquote|tr|section|article)\\b[^>]*>",
        RegexOption.IGNORE_CASE,
    )

    /** MOBI 分页标记 → 换行 */
    private val MBP_PAGEBREAK_REGEX = Regex("<mbp:pagebreak\\s*/?>", RegexOption.IGNORE_CASE)

    /** 残余所有标签 */
    private val ANY_TAG_REGEX = Regex("<[^>]*>")

    /** <title> 提取（EPUB 章标题用） */
    private val TITLE_REGEX = Regex(
        "<title[^>]*>(.*?)</title>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** MOBI 标题标签（分章用） */
    private val HEADING_REGEX = Regex(
        "<h[1-6][^>]*>(.*?)</h[1-6]>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

    /** 命名实体映射（手写表 + 数字实体正则解码，不引第三方库） */
    private val NAMED_ENTITIES: Map<String, String> = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to "\u00A0", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
        "middot" to "·", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
    )

    private val ENTITY_REGEX = Regex("&(?:#x([0-9a-fA-F]+)|#([0-9]+)|([a-zA-Z][a-zA-Z0-9]*));")

    /** HTML 实体解码：&#xNN; / &#NNN;（含增补平面代理对还原）+ 常用命名实体；未知实体原样保留 */
    fun decodeEntities(s: String): String = ENTITY_REGEX.replace(s) { m ->
        val hex = m.groupValues[1]
        val dec = m.groupValues[2]
        val named = m.groupValues[3]
        when {
            hex.isNotEmpty() -> hex.toIntOrNull(16)?.let { codePointToString(it) }
            dec.isNotEmpty() -> dec.toIntOrNull()?.let { codePointToString(it) }
            else -> NAMED_ENTITIES[named.lowercase(Locale.US)]
        } ?: m.value
    }

    /** 码点 → 字符串：增补平面（>0xFFFF）走代理对，其余单 char；非法码点返回 null（实体原样保留） */
    private fun codePointToString(cp: Int): String? {
        if (cp < 0 || cp > 0x10FFFF) return null
        return if (cp > Char.MAX_VALUE.code) String(Character.toChars(cp)) else cp.toChar().toString()
    }

    /**
     * HTML → 纯文本段落列表：
     * 1) 去 <head> 整块（title/meta 不属于正文，否则封面页会只剩"封面"二字）、<style>/<script> 块与注释；
     * 2) <br> 与块级标签边界转换行；3) 正则去全部剩余标签；
     * 4) 实体解码；5) 按换行切分、trim、去空行。每行即一段（EPUB/MOBI 源文件的段落即行）。
     */
    fun htmlToParagraphs(html: String): List<String> {
        var s = html
        s = HEAD_REGEX.replace(s, "")
        s = SCRIPT_STYLE_REGEX.replace(s, "")
        s = COMMENT_REGEX.replace(s, "")
        s = BR_REGEX.replace(s, "\n")
        s = BLOCK_TAG_REGEX.replace(s, "\n")
        s = MBP_PAGEBREAK_REGEX.replace(s, "\n")
        s = ANY_TAG_REGEX.replace(s, "")
        s = decodeEntities(s)
        return s.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /** 提取 <title> 文本（去标签 + 实体解码 + 压缩空白），无 title 或为空返回 null */
    private fun extractHtmlTitle(html: String): String? {
        val raw = TITLE_REGEX.find(html)?.groupValues?.get(1) ?: return null
        val title = decodeEntities(ANY_TAG_REGEX.replace(raw, ""))
            .replace(Regex("\\s+"), " ").trim()
        return title.ifEmpty { null }
    }

    /**
     * MOBI 全文（单文档 HTML）→ 段落 + 章节标记（MVP 分章）：
     * 按 <h1>-<h6> 块分节：每个标题块生成一个章节标记 + 标题段落（与 TXT 的"章首单独成段"一致），
     * 标题之间的内容清洗为普通段落；首个标题之前的内容归入隐式"正文"章。
     * 无任何标题 → 不分章（chapters 为空列表，目录抽屉兜底书签）。
     */
    fun mobiHtmlToBook(html: String): Pair<List<Paragraph>, List<ChapterMark>> {
        val paragraphs = mutableListOf<Paragraph>()
        val marks = mutableListOf<ChapterMark>()
        val headings = HEADING_REGEX.findAll(html).toList()
        if (headings.isEmpty()) {
            htmlToParagraphs(html).forEach { paragraphs += Paragraph(text = it, chapterIndex = 0) }
            return paragraphs to marks // 空标记列表：无章节结构
        }

        // 首个标题之前的正文 → 隐式"正文"章（内容可能为空：扉页版权信息等，空则跳过）
        val preamble = htmlToParagraphs(html.substring(0, headings[0].range.first))
        if (preamble.isNotEmpty()) {
            marks += ChapterMark(title = "正文", paragraphIndex = paragraphs.size)
            preamble.forEach { paragraphs += Paragraph(text = it, chapterIndex = marks.size - 1) }
        }
        headings.forEachIndexed { index, match ->
            // 标题文本：去标签 + 实体解码 + 压缩空白，超长截断
            val title = decodeEntities(ANY_TAG_REGEX.replace(match.groupValues[1], ""))
                .replace(Regex("\\s+"), " ").trim()
                .take(50)
                .ifEmpty { "第 ${index + 1} 节" }
            marks += ChapterMark(title = title, paragraphIndex = paragraphs.size)
            paragraphs += Paragraph(text = title, chapterIndex = marks.size - 1)
            // 标题之后、下一个标题之前的正文
            val segmentEnd = headings.getOrNull(index + 1)?.range?.first ?: html.length
            htmlToParagraphs(html.substring(match.range.last + 1, segmentEnd))
                .forEach { paragraphs += Paragraph(text = it, chapterIndex = marks.size - 1) }
        }
        return paragraphs to marks
    }

    // ==================================================================
    // 小工具（PDB 读取与 zip 条目文本；偏移逻辑与 MobiPageSource 保持一致的对照注释）
    // ==================================================================

    /** PDB 头固定部分长度（记录数字段 76..78 结束） */
    private const val PDB_HEADER_SIZE = 78

    /** 记录偏移表条目长度：offset(4B) + attributes(1B) + uniqueID(3B) */
    private const val PDB_ENTRY_SIZE = 8

    /** MOBI 魔数在 record0 内的偏移（PalmDOC header 长 16 字节） */
    private const val MOBI_MAGIC_AT = 16

    /** "MOBI" 的 32 位大端魔数值 */
    private const val MOBI_MAGIC = 0x4D4F4249L

    /** MOBI headerLength 字段在 record0 内的偏移（MOBI 魔数后 +4） */
    private const val MOBI_HEADER_LENGTH_AT = 20

    /** MOBI header 需覆盖到 firstImageIndex（魔数后 +92）才认为该字段可信 */
    private const val FIRST_IMAGE_INDEX_IN_HEADER = 96L

    /** textEncoding 字段在 record0 内的偏移（MOBI 魔数后 +28） */
    private const val TEXT_ENCODING_AT = 44

    /** firstImageIndex 字段在 record0 内的偏移（MOBI 魔数后 +92） */
    private const val FIRST_IMAGE_INDEX_AT = 108

    /** PalmDOC compression = HUFF/CDIC（17480）：Amazon DRM 专用 */
    private const val COMPRESSION_HUFF_CDIC = 17480

    /** record0 解析需要读到的字段末尾（firstImageIndex 占 108..112） */
    private const val RECORD0_REQUIRED = FIRST_IMAGE_INDEX_AT + 4

    /** record0 读取上限：覆盖全部所需字段并留余量 */
    private const val RECORD0_READ_SIZE = 256

    /** MOBI 单条记录的解压/读取上限（正常 PalmDOC 记录 4096 字节，超限视为损坏防御） */
    private const val MAX_MOBI_RECORD_BYTES = 1024 * 1024

    /** 大端读 4 字节为无符号长整型（0xFFFFFFFF 需与 Long 比较，避免 Int 符号位坑） */
    private fun readUIntBE(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xFF) shl 24) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 8) or
            (bytes[offset + 3].toLong() and 0xFF)

    /** 大端读 2 字节为无符号整型 */
    private fun readUShortBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    /** 从通道当前 position 读至多 max 字节，返回实际读到的数组（提前 EOF 截断） */
    private fun readUpTo(channel: SeekableByteChannel, max: Int): ByteArray {
        val dst = ByteBuffer.allocate(max)
        while (dst.hasRemaining()) {
            if (channel.read(dst) <= 0) break
        }
        return if (dst.hasRemaining()) dst.array().copyOf(dst.position()) else dst.array()
    }

    /** 读取 zip 条目全文并按 UTF-8 解码 */
    private fun readEntryText(zip: ZipFile, entry: ZipArchiveEntry): String =
        zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }

    /** href/路径的 URL 解码（%20 等）；失败回退原值 */
    private fun urlDecode(s: String): String =
        runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
