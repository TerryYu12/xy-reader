package com.xyreader.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/** 阅读器首次打开的起始页选择：restart > 书签页 > 已存进度。 */
class ReaderStartPageTest {
    @Test fun restartIgnoresSavedProgressAndExplicitPage() {
        assertEquals(0, firstOpenStartPage(startFromBeginning = true, initialPage = 0, bookCurrentPage = 5))
        assertEquals(0, firstOpenStartPage(startFromBeginning = true, initialPage = 9, bookCurrentPage = 5))
    }

    @Test fun explicitPageWinsOverSavedProgress() {
        assertEquals(9, firstOpenStartPage(startFromBeginning = false, initialPage = 9, bookCurrentPage = 5))
    }

    @Test fun plainOpenResumesSavedProgress() {
        assertEquals(5, firstOpenStartPage(startFromBeginning = false, initialPage = 0, bookCurrentPage = 5))
    }

    @Test fun unreadBookStartsAtZero() {
        assertEquals(0, firstOpenStartPage(startFromBeginning = false, initialPage = 0, bookCurrentPage = 0))
    }
}
