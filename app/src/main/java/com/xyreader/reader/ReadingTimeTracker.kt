package com.xyreader.reader

/**
 * 阅读时长计时器（纯逻辑，时钟由外部注入，便于单测）。
 *
 * 规则：
 * - 只在前台累计：[onResume] 开始、[onPause] 结束，后台期间的 [onInteraction] 一律忽略；
 * - 计入区间截止到「最后一次交互 + [idleTimeoutMs]」：超过 2 分钟没有交互视为放下设备，
 *   之后的时间不计，直到下一次交互；
 * - [onResume] 本身视为一次交互；
 * - [drain] 先把到目前为止的时长结算，再取出并清零，供落库；落库失败用 [restore] 放回。
 *
 * [clock] 须为单调时钟（毫秒），如 SystemClock.elapsedRealtime；系统时间被改动不会影响计时。
 * 所有方法加了同步，可在主线程交互与 IO 线程落库间并发调用。
 */
class ReadingTimeTracker(
    private val clock: () -> Long,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    private val interactionThrottleMs: Long = INTERACTION_THROTTLE_MS,
) {
    private var foreground = false

    /** 已结算到的时间点；其后的区间尚未计入 [pendingMs] */
    private var settledAt = 0L
    private var lastInteractionAt = 0L
    private var pendingMs = 0L

    /** 回到前台：开始计时，并视为一次交互；已在前台则只当作交互 */
    @Synchronized
    fun onResume() {
        val now = clock()
        if (foreground) {
            settle(now)
        } else {
            foreground = true
            settledAt = now
        }
        lastInteractionAt = now
    }

    /** 离开前台：先结算再停表 */
    @Synchronized
    fun onPause() {
        if (!foreground) return
        settle(clock())
        foreground = false
    }

    /** 一次用户交互（触摸、翻页）；后台忽略，1 秒内的重复交互合并以减少开销 */
    @Synchronized
    fun onInteraction() {
        if (!foreground) return
        val now = clock()
        val sinceLast = now - lastInteractionAt
        if (sinceLast >= 0L && sinceLast < interactionThrottleMs) return
        // 必须先结算：长时间无交互后的这次交互不能把空闲区间补算进来
        settle(now)
        lastInteractionAt = now
    }

    /** 结算后取出尚未落库的时长并清零 */
    @Synchronized
    fun drain(): Long {
        if (foreground) settle(clock())
        val value = pendingMs
        pendingMs = 0L
        return value
    }

    /** 当前尚未落库的时长（含未结算部分），只读不清零，用于「今日 N 分钟」显示 */
    @Synchronized
    fun peekPending(): Long = pendingMs + if (foreground) unsettled(clock()) else 0L

    /** 落库失败时把 [ms] 放回，下次一并写入 */
    @Synchronized
    fun restore(ms: Long) {
        if (ms > 0) pendingMs += ms
    }

    /** [settledAt, now] 中落在「最后交互 + 空闲上限」之内的部分 */
    private fun unsettled(now: Long): Long {
        val end = minOf(now, lastInteractionAt + idleTimeoutMs)
        return (end - settledAt).coerceAtLeast(0L)
    }

    private fun settle(now: Long) {
        pendingMs += unsettled(now)
        settledAt = maxOf(settledAt, now)
    }

    companion object {
        const val IDLE_TIMEOUT_MS = 2 * 60_000L
        const val INTERACTION_THROTTLE_MS = 1_000L
    }
}
