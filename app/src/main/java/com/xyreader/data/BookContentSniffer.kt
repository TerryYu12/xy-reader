package com.xyreader.data

import com.xyreader.core.BookFormat
import java.io.File
import java.util.zip.ZipFile

/**
 * 书籍文件的内容嗅探与「声明格式 ↔ 实际内容」一致性校验。
 *
 * 导入管线与下载入库共用同一份判定，避免两处规则漂移；
 * 纯文件操作、不依赖 Context，调用方应在 IO 线程调用。
 */
internal object BookContentSniffer {

    /**
     * 文件头魔数嗅探：名称与 MIME 都定不了类型时的最后兜底，返回扩展名（识别不了为 null）。
     * 覆盖：%PDF→pdf；PK→epub（mimetype 条目）/zip；Rar!→rar；7z 魔数→7z；
     * PDB/BOOKMOBI→mobi；前 1KB 无 NUL 字节→txt。
     */
    fun sniffExtension(file: File): String? {
        val head = ByteArray(1024)
        val len = file.inputStream().use { it.read(head) }
        if (len <= 0) return null
        fun byteAt(i: Int) = if (i < len) head[i].toInt() and 0xFF else -1
        return when {
            len >= 4 && byteAt(0) == 0x25 && byteAt(1) == 0x50 &&
                byteAt(2) == 0x44 && byteAt(3) == 0x46 -> "pdf" // %PDF
            len >= 4 && byteAt(0) == 0x50 && byteAt(1) == 0x4B ->
                if (zipLooksLikeEpub(file)) "epub" else "zip" // PK\x03\x04
            byteAt(0) == 0x52 && byteAt(1) == 0x61 &&
                byteAt(2) == 0x72 && byteAt(3) == 0x21 -> "rar" // Rar!
            len >= 4 && byteAt(0) == 0x37 && byteAt(1) == 0x7A &&
                byteAt(2) == 0xBC && byteAt(3) == 0xAF -> "7z"
            len >= 68 && String(head, 60, 8, Charsets.US_ASCII) in setOf("BOOKMOBI", "TEXtREAd") -> "mobi"
            (0 until len).none { head[it] == 0.toByte() } -> "txt"
            else -> null
        }
    }

    /** zip 家族判定：含 mimetype 条目（EPUB 规范首条目）→ epub；读取失败按普通 zip 处理 */
    fun zipLooksLikeEpub(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry("mimetype") != null ||
                zip.entries().asSequence().take(5).any { it.name == "mimetype" }
        }
    }.getOrDefault(false)

    /**
     * 前 1KB 是否像 HTML。
     *
     * 最典型的坏响应是登录页 / 拦截页 / 错误页被当成书籍文件下载下来：
     * 它们是纯文本，魔数嗅探只会得到 `txt`，光靠扩展名会一路入库成一堆乱码书。
     */
    fun looksLikeHtml(file: File): Boolean {
        val head = ByteArray(1024)
        val len = file.inputStream().use { it.read(head) }
        if (len <= 0) return false
        val text = String(head, 0, len, Charsets.ISO_8859_1).trimStart().lowercase()
        return text.startsWith("<!doctype html") || text.startsWith("<html")
    }

    /** 声明格式允许被嗅探成哪些结果（zip 家族成员之间互相兼容，不当作内容错误） */
    private val acceptableSniffs: Map<BookFormat, Set<String>> = mapOf(
        BookFormat.CBZ to setOf("zip", "epub"),
        BookFormat.CBR to setOf("rar"),
        BookFormat.CB7 to setOf("7z"),
        BookFormat.CBT to setOf("tar"),
        BookFormat.EPUB to setOf("epub", "zip"),
        BookFormat.PDF to setOf("pdf"),
        BookFormat.MOBI to setOf("mobi"),
        BookFormat.AZW3 to setOf("mobi"),
        BookFormat.TXT to setOf("txt"),
        BookFormat.MD to setOf("txt"),
    )

    /**
     * 声明的格式与嗅探结果是否相容。
     *
     * 嗅探不出（null，例如 TAR）时放行——宁可交给阅读器兜底，也不误杀正常文件；
     * 判定表里没有的声明格式同样放行。真正要拦的是「声明 EPUB 实际是文本/PDF/压缩包」这类
     * 明确矛盾：那意味着拿到的是登录页、错误页或别的文件。
     */
    fun declaredMatchesSniffed(declared: BookFormat, sniffed: String?): Boolean {
        if (sniffed == null) return true
        val acceptable = acceptableSniffs[declared] ?: return true
        return sniffed in acceptable
    }
}
