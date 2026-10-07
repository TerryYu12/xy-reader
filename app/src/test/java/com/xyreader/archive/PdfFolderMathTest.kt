package com.xyreader.archive

import com.xyreader.core.Chapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PDF 合集页码换算（纯函数）：起始页前缀和、全书页码 → (文件序号, 文件内页码) 的边界定位、
 * 越界异常与章节区间。
 */
class PdfFolderMathTest {

    /** 三个文件依次 3 / 5 / 2 页：起始页 [0, 3, 8]，全书 10 页 */
    private val pageCounts = listOf(3, 5, 2)
    private val starts = PdfFolderMath.startPages(pageCounts)
    private val total = pageCounts.sum()

    @Test
    fun `起始页是页数的前缀和`() {
        assertArrayEquals(intArrayOf(0, 3, 8), starts)
    }

    @Test
    fun `单个文件的起始页只有 0`() {
        assertArrayEquals(intArrayOf(0), PdfFolderMath.startPages(listOf(7)))
    }

    @Test
    fun `第一页落在第一个文件的第 0 页`() {
        assertEquals(0 to 0, PdfFolderMath.locate(starts, total, 0))
    }

    @Test
    fun `文件边界前一页是前一个文件的最后一页`() {
        assertEquals(0 to 2, PdfFolderMath.locate(starts, total, 2))
        assertEquals(1 to 4, PdfFolderMath.locate(starts, total, 7))
    }

    @Test
    fun `文件边界上的页是后一个文件的第 0 页`() {
        assertEquals(1 to 0, PdfFolderMath.locate(starts, total, 3))
        assertEquals(2 to 0, PdfFolderMath.locate(starts, total, 8))
    }

    @Test
    fun `文件内的中间页换算正确`() {
        assertEquals(1 to 2, PdfFolderMath.locate(starts, total, 5))
    }

    @Test
    fun `最后一页落在最后一个文件的末页`() {
        assertEquals(2 to 1, PdfFolderMath.locate(starts, total, 9))
    }

    @Test
    fun `每一页都能由文件起始页加文件内页码还原`() {
        for (page in 0 until total) {
            val (file, local) = PdfFolderMath.locate(starts, total, page)
            assertEquals(page, starts[file] + local)
            assertTrue(local in 0 until pageCounts[file])
        }
    }

    @Test
    fun `单文件时全书页码就是文件内页码`() {
        val single = PdfFolderMath.startPages(listOf(12))
        assertEquals(0 to 0, PdfFolderMath.locate(single, 12, 0))
        assertEquals(0 to 11, PdfFolderMath.locate(single, 12, 11))
    }

    @Test
    fun `大量单页与多页混合的文件二分结果与逐页游标一致`() {
        // 页数在 1..13 间不等，文件数多到让二分走满好几层
        val counts = (1..60).map { (it * 7) % 13 + 1 }
        val s = PdfFolderMath.startPages(counts)
        val sum = counts.sum()
        var file = 0
        var local = 0
        for (page in 0 until sum) {
            assertEquals("第 $page 页", file to local, PdfFolderMath.locate(s, sum, page))
            local++
            if (local == counts[file]) {
                file++
                local = 0
            }
        }
    }

    @Test
    fun `负数页码抛越界异常`() {
        assertThrows(IndexOutOfBoundsException::class.java) {
            PdfFolderMath.locate(starts, total, -1)
        }
    }

    @Test
    fun `页码等于总页数或更大抛越界异常`() {
        assertThrows(IndexOutOfBoundsException::class.java) {
            PdfFolderMath.locate(starts, total, total)
        }
        assertThrows(IndexOutOfBoundsException::class.java) {
            PdfFolderMath.locate(starts, total, total + 5)
        }
    }

    @Test
    fun `章节区间首尾相接并覆盖全书`() {
        val chapters = PdfFolderMath.chapters(listOf("第1卷", "第2卷", "第3卷"), pageCounts)
        assertEquals(
            listOf(
                Chapter("第1卷", 0, 2),
                Chapter("第2卷", 3, 7),
                Chapter("第3卷", 8, 9),
            ),
            chapters,
        )
    }

    @Test
    fun `单文件只有一章并覆盖全书`() {
        assertEquals(
            listOf(Chapter("唯一", 0, 11)),
            PdfFolderMath.chapters(listOf("唯一"), listOf(12)),
        )
    }

    @Test
    fun `只有一页的文件是起止相同的单页章节`() {
        assertEquals(
            listOf(
                Chapter("a", 0, 0),
                Chapter("b", 1, 1),
                Chapter("c", 2, 4),
            ),
            PdfFolderMath.chapters(listOf("a", "b", "c"), listOf(1, 1, 3)),
        )
    }

    @Test
    fun `标题数与页数个数不一致时拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            PdfFolderMath.chapters(listOf("a", "b"), listOf(3))
        }
    }
}
