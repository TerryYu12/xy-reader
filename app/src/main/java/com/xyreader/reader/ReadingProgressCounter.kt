package com.xyreader.reader

/**
 * 阅读字数 / 页数计数器（纯逻辑）：按「到达新页」计数。
 *
 * - 起始页只记为已见，不计；
 * - 向前翻 1 ～ [maxStep] 页：把 (上次页, 本页] 内没见过的页计入；
 * - 回翻、跳转（跨度超过 [maxStep]）只移动基线并把落点记为已见，不计；
 * - 同一页反复进出不会重复计入。
 *
 * 文字书（[textBook]）只累加字数，图片类书籍只累加页数。
 * [charsOfPage] 给出某页的字数（NovelPageSource.pageCharCount），仅文字书会调用。
 * 样式重排换页码体系时，调用方用新的起始页重建本对象即可。
 */
class ReadingProgressCounter(
    startPage: Int,
    private val textBook: Boolean,
    private val charsOfPage: (Int) -> Long,
    private val maxStep: Int = MAX_FORWARD_STEP,
) {
    /** 一次翻页新增的字数 / 页数 */
    data class Gain(val chars: Long, val pages: Int) {
        val isEmpty: Boolean get() = chars == 0L && pages == 0
    }

    private var lastPage = startPage
    private val seen = HashSet<Int>().apply { add(startPage) }

    /** 回报当前页，返回这次新增的量；调用方累加后落库 */
    @Synchronized
    fun onPage(page: Int): Gain {
        val previous = lastPage
        lastPage = page
        if (page == previous) return NO_GAIN
        if (page < previous || page - previous > maxStep) {
            seen += page
            return NO_GAIN
        }
        var chars = 0L
        var pages = 0
        for (p in previous + 1..page) {
            if (!seen.add(p)) continue
            if (textBook) {
                chars += charsOfPage(p).coerceAtLeast(0L)
            } else {
                pages++
            }
        }
        return if (chars == 0L && pages == 0) NO_GAIN else Gain(chars, pages)
    }

    companion object {
        /** 单次前进超过该页数视为跳转 */
        const val MAX_FORWARD_STEP = 3
        private val NO_GAIN = Gain(0L, 0)
    }
}
