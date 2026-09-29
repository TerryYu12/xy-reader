package com.xyreader.data

import android.app.Application
import com.xyreader.core.BookEntity
import com.xyreader.core.SortOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryMutationTest {
    @Test fun groupRemovalPreservesBookAndDeletingBookRemovesItsBookmarksOnly() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val repo = AppGraph.libraryRepository(context)
        val dao = ArkDatabase.getInstance(context).bookDao()
        val original = File(context.filesDir, "original.txt").apply { writeText("原文件必须保留") }
        val first = dao.insertBook(BookEntity(title = "小说一", uri = original.toURI().toString(), format = "TXT", addedAt = 1))
        val second = dao.insertBook(BookEntity(title = "小说二", uri = "file:///second.txt", format = "TXT", addedAt = 2))
        val group = repo.addGroup("待读")
        repo.moveBookToGroup(first, group)
        assertEquals(listOf(first), repo.booksInGroup(group, SortOption.ADDED).first().map { it.id })
        repo.moveBookToGroup(first, null)
        assertNull(dao.getById(first)!!.groupId)
        repo.moveBookToGroup(first, group)
        repo.removeGroup(group)
        assertNotNull(dao.getById(first))
        assertNull(dao.getById(first)!!.groupId)
        repo.addBookmark(first, 3)
        repo.addBookmark(second, 4)
        repo.deleteBook(first)
        assertNull(dao.getById(first))
        assertNotNull(dao.getById(second))
        assertEquals(listOf(second), repo.bookmarks.first().map { it.bookId })
        assertEquals("原文件必须保留", original.readText())
    }
}
