package com.xyreader.stats

/** 时长文案：不足一小时「Y 分钟」，否则「X 小时 Y 分钟」（分钟向下取整，负值按 0） */
fun formatDuration(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours 小时 $minutes 分钟" else "$minutes 分钟"
}

/**
 * 字数文案：不足一万「N 字」，一万以上「12.3 万字」，一亿以上「1.2 亿字」。
 * 小数一律向下截断到一位，避免 99999999 被四舍五入成「10000.0 万字」。
 */
fun formatChars(count: Long): String {
    val n = count.coerceAtLeast(0L)
    return when {
        n < 10_000L -> "$n 字"
        n < 100_000_000L -> {
            val tenths = n / 1_000L
            "${tenths / 10}.${tenths % 10} 万字"
        }
        else -> {
            val tenths = n / 10_000_000L
            "${tenths / 10}.${tenths % 10} 亿字"
        }
    }
}

/** 页数文案：「N 页」 */
fun formatPages(count: Long): String = "${count.coerceAtLeast(0L)} 页"

/** 阅读器底栏「今日 N 分钟」：不足一分钟显示「今日 <1 分钟」，否则向下取整 */
fun formatTodayMinutes(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000L
    return if (minutes <= 0L) "今日 <1 分钟" else "今日 $minutes 分钟"
}
