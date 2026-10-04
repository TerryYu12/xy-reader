package com.xyreader.data

import android.app.Application
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LibraryLayoutStoreTest {
    @Test
    fun shelfCabinetModeDefaultsToCabinetAndPersistsIndependentlyOfHomeOrder() = runBlocking {
        assertTrue(LibraryLayout().shelfCabinetView)

        val context = RuntimeEnvironment.getApplication()
        val store = LibraryLayoutStore(context)
        assertTrue(store.layout.first().shelfCabinetView)
        store.setShelfCabinetView(true)
        store.setHomeManualOrder(listOf(8L, 3L, 5L))
        store.setShelfCabinetView(false)

        val gridLayout = LibraryLayoutStore(context).layout.first()
        assertFalse(gridLayout.shelfCabinetView)
        assertEquals(listOf(8L, 3L, 5L), gridLayout.homeBookOrder)
        assertTrue(gridLayout.homeManualOrder)

        store.setShelfCabinetView(true)
        assertTrue(LibraryLayoutStore(context).layout.first().shelfCabinetView)
    }
}
