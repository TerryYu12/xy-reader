package com.xyreader.data

import com.xyreader.core.BookEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryOrderTest {
    private fun books(vararg ids: Long) = ids.map {
        BookEntity(id = it, title = "书$it", uri = "file:///$it.txt", format = "TXT", addedAt = it)
    }

    @Test fun restoredOrderIgnoresDeletedBooksAndKeepsNewImports() {
        assertEquals(listOf(3L, 1L, 2L, 4L), applyHomeBookOrder(books(1, 2, 3, 4), listOf(3, 99, 1)).map { it.id })
    }

    @Test fun filteredReorderPreservesHiddenBookPositions() {
        assertEquals(listOf(3L, 2L, 1L, 4L), reorderVisibleBookIds(listOf(1, 2, 3, 4), listOf(1, 3), listOf(3, 1)))
    }

    @Test fun partialReorderDoesNotDropUnmovedBooks() {
        assertEquals(listOf(3L, 2L, 1L, 4L), reorderVisibleBookIds(listOf(1, 2, 3, 4), listOf(1, 3), listOf(3)))
    }

    @Test fun emptyFilterLeavesOrderUntouched() {
        assertEquals(listOf(1L, 2L), reorderVisibleBookIds(listOf(1, 2), emptyList(), emptyList()))
    }

    @Test fun staleVisibleIdsCannotReplaceBooksThatStillExist() {
        assertEquals(listOf(1L, 2L), reorderVisibleBookIds(listOf(1, 2), listOf(1, 3), listOf(3, 1)))
    }
}
