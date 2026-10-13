package com.xyreader.stats

import java.time.LocalDate

/**
 * 「刚刚打卡成功」的检测器（纯逻辑，无 Android 依赖）。
 *
 * 阅读器会反复上报「今日累计阅读时长」；只有在**本次阅读过程中**亲眼看到它从阈值以下
 * 越过阈值，才算一次打卡成功。因此：
 * - 同一天的第一次上报只记基线、不触发——打开书时今天早就读满了，不该再放一遍动画；
 * - 越线之后同一天不再触发；
 * - 日期变了（跨零点继续读）按新的一天重新记基线。
 *
 * 非线程安全：调用方需串行调用（阅读器里在落库互斥锁内调用）。
 */
class CheckInDetector(private val thresholdMs: Long = CHECK_IN_THRESHOLD_MS) {
    private var date: LocalDate? = null
    private var lastMs = 0L

    /** 上报 [date] 当天截至目前的累计阅读时长 [todayMs]；这一次刚好越过打卡线时返回 true */
    fun observe(date: LocalDate, todayMs: Long): Boolean {
        val crossed = this.date == date && lastMs < thresholdMs && todayMs >= thresholdMs
        this.date = date
        lastMs = todayMs
        return crossed
    }
}
