package com.xyreader.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 书库多选状态的纯逻辑回归：进入 / 切换 / 全选 / 剔除失效 id / 退出。 */
class BookSelectionStateTest {

    @Test
    fun startsInactiveWithNothingSelected() {
        val state = BookSelectionState()
        assertFalse(state.active)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun enterActivatesAndSelectsTheGivenBook() {
        val state = BookSelectionState()
        state.enter(7L)
        assertTrue(state.active)
        assertEquals(setOf(7L), state.selectedIds)
    }

    @Test
    fun enterWithoutBookOnlyActivates() {
        val state = BookSelectionState()
        state.enter()
        assertTrue(state.active)
        assertTrue(state.selectedIds.isEmpty())
    }

    @Test
    fun enterAgainWhileActiveAddsToTheSelection() {
        val state = BookSelectionState()
        state.enter(7L)
        state.enter(8L)
        assertTrue(state.active)
        assertEquals(setOf(7L, 8L), state.selectedIds)
    }

    @Test
    fun toggleAddsThenRemovesWithoutLeavingSelectionMode() {
        val state = BookSelectionState()
        state.enter()
        state.toggle(3L)
        state.toggle(4L)
        assertEquals(setOf(3L, 4L), state.selectedIds)
        state.toggle(3L)
        assertEquals(setOf(4L), state.selectedIds)
        state.toggle(4L)
        // 取消勾选最后一本也不会自动退出多选
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(state.active)
    }

    @Test
    fun selectAllReplacesTheSelectionAndClearKeepsTheMode() {
        val state = BookSelectionState()
        state.enter(9L)
        state.selectAll(listOf(1L, 2L, 3L, 2L))
        assertEquals(setOf(1L, 2L, 3L), state.selectedIds)

        state.clearSelection()
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(state.active)
    }

    @Test
    fun retainDropsIdsThatLeftTheList() {
        val state = BookSelectionState()
        state.enter()
        state.selectAll(listOf(1L, 2L, 3L))
        state.retain(setOf(2L, 3L, 4L))
        assertEquals(setOf(2L, 3L), state.selectedIds)

        // 列表里一本都不剩时勾选清空，但仍停留在多选模式（是否退出由 BookGrid 决定）
        state.retain(emptySet())
        assertTrue(state.selectedIds.isEmpty())
        assertTrue(state.active)
    }

    @Test
    fun exitClearsTheSelectionAndLeavesTheMode() {
        val state = BookSelectionState()
        state.enter(5L)
        state.toggle(6L)
        state.exit()
        assertFalse(state.active)
        assertTrue(state.selectedIds.isEmpty())

        // 再次进入时不会带着上一轮的勾选
        state.enter()
        assertTrue(state.active)
        assertTrue(state.selectedIds.isEmpty())
    }
}
