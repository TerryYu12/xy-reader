package com.xyreader.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuickReadTest {

    private fun book(id: Long, lastReadAt: Long? = null) = BookEntity(
        id = id,
        title = "书$id",
        uri = "file:///books/$id.txt",
        format = "TXT",
        addedAt = 0,
        lastReadAt = lastReadAt,
    )

    @Test
    fun `lastRead 取最近阅读的一本`() {
        val books = listOf(book(1, 100), book(2, null), book(3, 300), book(4, 200))
        assertEquals(3L, QuickRead.lastRead(books)?.id)
    }

    @Test
    fun `lastRead 无阅读记录时返回 null`() {
        assertNull(QuickRead.lastRead(listOf(book(1), book(2))))
        assertNull(QuickRead.lastRead(emptyList()))
    }

    @Test
    fun `random 空列表返回 null 且非空时总在范围内`() {
        assertNull(QuickRead.random(emptyList(), Random(7)))
        val books = listOf(book(1), book(2), book(3))
        repeat(60) { seed ->
            val picked = QuickRead.random(books, Random(seed))
            assertTrue("seed=$seed 应落在集合内", picked!!.id in 1L..3L)
        }
    }
}
