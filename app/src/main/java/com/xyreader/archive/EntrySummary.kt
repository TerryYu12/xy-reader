package com.xyreader.archive

import java.util.Locale

/**
 * 压缩包条目的诊断摘要（纯 Kotlin）：打开失败「没有图片页面」时附在异常信息里，
 * 随 BUG 反馈日志一起上传，据此判断是扩展名不被识别、图片藏在内层压缩包里，还是包里本来就没有图片。
 *
 * 只统计数量与扩展名，不含文件名，避免日志泄露书名等内容。
 */
internal object EntrySummary {

    /** 最多列出的扩展名种类 */
    private const val MAX_EXTENSIONS = 6

    /** 单个扩展名最多保留的字符数（超长多半不是扩展名，如文件名里恰好有个点） */
    private const val MAX_EXTENSION_CHARS = 8

    private const val NO_EXTENSION = "(无扩展名)"

    /**
     * 例：`条目 12 个（目录 2，被排除 1）；扩展名：.zip×5 .txt×2 (无扩展名)×2`。
     * [isExcluded] 判断非目录条目是否因隐藏 / `__MACOSX` 等被排除（见 [PageSources.isExcludedEntry]）。
     */
    fun describe(names: Sequence<String>, isExcluded: (String) -> Boolean): String {
        var total = 0
        var dirs = 0
        var excluded = 0
        val counts = LinkedHashMap<String, Int>()
        for (raw in names) {
            total++
            val name = raw.replace('\\', '/')
            when {
                name.endsWith("/") -> dirs++
                isExcluded(name) -> excluded++
                else -> counts.merge(extensionOf(name), 1, Int::plus)
            }
        }
        if (total == 0) return "压缩包是空的"
        val head = buildString {
            append("条目 ").append(total).append(" 个")
            if (dirs > 0 || excluded > 0) {
                append("（目录 ").append(dirs).append("，被排除 ").append(excluded).append("）")
            }
        }
        if (counts.isEmpty()) return head
        val sorted = counts.entries.sortedByDescending { it.value }
        val shown = sorted.take(MAX_EXTENSIONS).joinToString(" ") { "${it.key}×${it.value}" }
        val more = sorted.size - MAX_EXTENSIONS
        return "$head；扩展名：$shown" + if (more > 0) " …另 $more 种" else ""
    }

    private fun extensionOf(name: String): String {
        val last = name.substringAfterLast('/')
        if (!last.contains('.')) return NO_EXTENSION
        val ext = last.substringAfterLast('.').lowercase(Locale.US)
        if (ext.isEmpty()) return NO_EXTENSION
        // 只保留 ASCII 字母数字，其余（含中文）统一成 ?，保证日志里不会带出文件名片段
        val safe = ext.take(MAX_EXTENSION_CHARS).map { if (it in 'a'..'z' || it in '0'..'9') it else '?' }
        return "." + safe.joinToString("")
    }
}
