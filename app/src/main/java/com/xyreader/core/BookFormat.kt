package com.xyreader.core

/** 支持的书籍格式。识别规则：按文件扩展名。 */
enum class BookFormat(val displayName: String) {
    CBZ("ZIP/CBZ"),
    CBR("RAR/CBR"),
    CB7("7Z/CB7"),
    CBT("TAR/CBT"),
    PDF("PDF"),
    EPUB("EPUB"),
    MOBI("MOBI"),
    AZW3("AZW3/KF8"),
    TXT("TXT"),
    DIRECTORY("图片目录"),

    /** 同文件夹多个 PDF 合并成的合集（文件夹书：由扫描器按仓库规则生成，非扩展名识别，每个 PDF 为一章） */
    PDF_FOLDER("PDF 合集"),
    UNKNOWN("未知");

    companion object {
        private val archiveExts = setOf(
            "cbz", "zip", "cbr", "rar", "cb7", "7z", "cbt", "tar", "epub", "pdf",
            "mobi", "prc", "azw3", "azw", "txt",
        )

        /** 是否是可入库的漫画文件扩展名 */
        fun isSupportedFile(name: String): Boolean {
            val ext = name.substringAfterLast('.', "").lowercase()
            return ext in archiveExts
        }

        fun fromFileName(name: String): BookFormat {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "cbz", "zip" -> CBZ
                "cbr", "rar" -> CBR
                "cb7", "7z" -> CB7
                "cbt", "tar" -> CBT
                "pdf" -> PDF
                "epub" -> EPUB
                "mobi", "prc" -> MOBI
                "azw3", "azw" -> AZW3
                "txt" -> TXT
                else -> UNKNOWN
            }
        }
    }
}
