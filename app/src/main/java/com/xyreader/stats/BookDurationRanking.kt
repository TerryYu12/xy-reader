package com.xyreader.stats

/** 一本书的累计计时时长（排行榜原始数据，按书籍稳定 ID 归属） */
data class BookDurationStat(
    val bookId: Long,
    val title: String,
    val durationMs: Long,
)

/**
 * 柱状排行榜里的一根柱子。
 *
 * [fraction] 是相对最长那本的柱高比例（0f..1f），只用于画图；
 * 展示文案在数据层算好，界面层不再做单位换算。
 */
data class BookDurationBar(
    val stat: BookDurationStat,
    /** 相对最高柱的高度比例 */
    val fraction: Float,
    /** 柱顶可读时长，如「1 小时 5 分钟」 */
    val durationLabel: String,
    /** 点开后看的精确时长，如「1 小时 5 分 32 秒」 */
    val preciseLabel: String,
    /** 横轴简称：长书名截断，全名在点开后的弹窗里 */
    val shortTitle: String,
)

/** 长书名简称保留的字数（中文字符逐字计） */
const val SHORT_TITLE_MAX_CHARS = 5

/**
 * 长书名转简称：「一个很长很长的书名」→「一个很长很…」。
 * 只影响显示，不改变书名本身；返回为空时才退回「未命名」。
 */
fun shortBookTitle(title: String, maxChars: Int = SHORT_TITLE_MAX_CHARS): String {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return "未命名"
    if (maxChars <= 0 || trimmed.length <= maxChars) return trimmed
    return trimmed.take(maxChars) + "…"
}

/**
 * 生成竖向柱状排行榜数据：按时长降序，最长的一本作为满格。
 *
 * 只统计「有时长记录」的书：`durationMs <= 0` 的行会被丢掉，
 * 因此没有分书数据时返回空列表，界面显示「暂无分书时长记录」，
 * 与真实 0 分钟（有记录但不足一分钟）区分开。
 * 时长相同按书名升序，保证每次渲染顺序稳定。
 */
fun buildBookDurationRanking(rows: List<BookDurationStat>): List<BookDurationBar> {
    val recorded = rows
        .filter { it.durationMs > 0L }
        .sortedWith(compareByDescending<BookDurationStat> { it.durationMs }.thenBy { it.title })
    if (recorded.isEmpty()) return emptyList()
    val max = recorded.first().durationMs
    return recorded.map { stat ->
        BookDurationBar(
            stat = stat,
            fraction = (stat.durationMs.toFloat() / max.toFloat()).coerceIn(0f, 1f),
            durationLabel = formatDuration(stat.durationMs),
            preciseLabel = formatPreciseDuration(stat.durationMs),
            shortTitle = shortBookTitle(stat.title),
        )
    }
}
