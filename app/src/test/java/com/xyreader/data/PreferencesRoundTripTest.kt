package com.xyreader.data

import android.app.Application
import com.xyreader.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PreferencesRoundTripTest {
    @Test fun fontAndManualOrderSurviveStoreRecreation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val expected = ReaderPrefs(
            novelFontSizeSp = 27f,
            novelFontFamily = NovelFontFamily.SYSTEM_SERIF,
            novelFontWeight = NovelFontWeight.BOLD,
            novelLineSpacingMultiplier = 1.8f,
            novelMarginTopPx = 24f,
            novelMarginBottomPx = 32f,
            novelMarginLeftPx = 20f,
            novelMarginRightPx = 28f,
            novelLetterSpacingPx = 2f,
            pageMode = PageMode.UP_DOWN,
            novelChapterNewPage = false,
            imageQuality = ImageQuality.STANDARD,
        )
        ReaderPrefsStore(context).set(expected)
        assertEquals(expected, ReaderPrefsStore(context).prefs.first())
        LibraryLayoutStore(context).setHomeManualOrder(listOf(3, 1, 2))
        assertEquals(LibraryLayout(listOf(3, 1, 2), true), LibraryLayoutStore(context).layout.first())
        LibraryLayoutStore(context).useAutomaticHomeOrder()
        assertFalse(LibraryLayoutStore(context).layout.first().homeManualOrder)
        assertEquals(listOf(3L, 1L, 2L), LibraryLayoutStore(context).layout.first().homeBookOrder)
    }
}
