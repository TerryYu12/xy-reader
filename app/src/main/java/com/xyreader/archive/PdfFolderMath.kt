package com.xyreader.archive

import com.xyreader.core.Chapter

/**
 * PDF 合集的页码换算（纯 Kotlin、无 Android 依赖，便于单元测试）。
 *
 * 合集把同一文件夹里的多个 PDF 按文件名自然序首尾相接成一本书：全书页码是各文件页数的累加，
 * 每个文件是一章。本对象负责「文件序号 ↔ 全书页码」的换算与章节区间的推导。
 *
 * 约定：传入的各文件页数都 >= 1——0 页 / 无法读取的文件在探测阶段
 * （[PdfFolderPageSource.open]）就被跳过，不会进入这里。
 */
internal object PdfFolderMath {

    /**
     * 每个文件在全书中的起始页（0 起）：页数的前缀和。
     * 例如页数 [3, 5, 2] → [0, 3, 8]；返回数组与 [pageCounts] 等长、一一对应。
     */
    fun startPages(pageCounts: List<Int>): IntArray {
        val starts = IntArray(pageCounts.size)
        var offset = 0
        pageCounts.forEachIndexed { i, count ->
            starts[i] = offset
            offset += count
        }
        return starts
    }

    /**
     * 全书页码 → (文件序号, 文件内页码)，均为 0 起。
     * 二分查找最后一个 `startPages[i] <= index` 的文件；[index] 越界
     * （< 0 或 >= [totalPages]）抛 [IndexOutOfBoundsException]。
     */
    fun locate(startPages: IntArray, totalPages: Int, index: Int): Pair<Int, Int> {
        if (index < 0 || index >= totalPages) {
            throw IndexOutOfBoundsException("页码越界: $index / $totalPages")
        }
        var low = 0
        var high = startPages.size - 1
        while (low < high) {
            // 取上中位，保证 low 能走到「最后一个 <= index」的下标
            val mid = (low + high + 1) ushr 1
            if (startPages[mid] <= index) low = mid else high = mid - 1
        }
        return low to (index - startPages[low])
    }

    /**
     * 每个文件一章：标题取 [titles]，页区间为该文件在全书中占的闭区间（startPage ~ endPageInclusive）。
     * [titles] 与 [pageCounts] 必须等长且一一对应；返回结果按 startPage 升序（即文件顺序）。
     */
    fun chapters(titles: List<String>, pageCounts: List<Int>): List<Chapter> {
        require(titles.size == pageCounts.size) {
            "标题数与页数列表长度不一致: ${titles.size} / ${pageCounts.size}"
        }
        val starts = startPages(pageCounts)
        return titles.mapIndexed { i, title ->
            Chapter(
                title = title,
                startPage = starts[i],
                endPageInclusive = starts[i] + pageCounts[i] - 1,
            )
        }
    }
}
