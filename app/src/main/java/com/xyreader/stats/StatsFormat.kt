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

/**
 * 精确到秒的时长文案：「X 小时 Y 分 Z 秒」，删掉为 0 的高位。
 * 用于排行榜点开后查看精确时长；调用方一般还会同时展示 [formatDuration] 的可读版本。
 */
fun formatPreciseDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1_000L
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return buildString {
        if (hours > 0) append("$hours 小时 ")
        if (hours > 0 || minutes > 0) append("$minutes 分 ")
        append("$seconds 秒")
    }
}

/**
 * 打卡成功提示的副标题：「今日阅读已满 5 分钟」，连续 2 天及以上追加「 · 已连续打卡 N 天」。
 * [streak] ≤ 1（含算不出的 0）时不提连续天数，避免出现「已连续打卡 1 天」这种别扭的话。
 */
fun formatCheckInSubtitle(streak: Int, thresholdMs: Long = CHECK_IN_THRESHOLD_MS): String {
    val base = "今日阅读已满 ${thresholdMs / 60_000L} 分钟"
    return if (streak >= 2) "$base · 已连续打卡 $streak 天" else base
}

/** 阅读器底栏「今日 N 分钟」：不足一分钟显示「今日 <1 分钟」，否则向下取整 */
fun formatTodayMinutes(ms: Long): String {
    val minutes = ms.coerceAtLeast(0L) / 60_000L
    return if (minutes <= 0L) "今日 <1 分钟" else "今日 $minutes 分钟"
}
