package com.xyreader.data

import android.app.Application
import com.xyreader.core.DailyReadingEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ReadingStatsDao 在真实 Room 上的行为：同日累加、不同日分行、按日期升序。
 * 用 1999 年的假日期，结束时清理，避免污染其他测试共用的数据库文件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingStatsDaoTest {

    private val day1 = "1999-01-01"
    private val day2 = "1999-01-02"
    private val day3 = "1999-02-01"

    private suspend fun cleanUp(dao: ReadingStatsDao) {
        listOf(day1, day2, day3).forEach { dao.deleteByDate(it) }
    }

    @Test
    fun addDeltaAccumulatesWithinTheSameDay() = runBlocking {
        val dao = ArkDatabase.getInstance(RuntimeEnvironment.getApplication()).readingStatsDao()
        try {
            assertNull(dao.get(day1))

            dao.addDelta(day1, durationMs = 1_000, chars = 10, pages = 1)
            assertEquals(DailyReadingEntity(day1, 1_000, 10, 1), dao.get(day1))

            // 第二次不会被 IGNORE 占位覆盖，而是在原值上累加
            dao.addDelta(day1, durationMs = 2_500, chars = 5, pages = 2)
            assertEquals(DailyReadingEntity(day1, 3_500, 15, 3), dao.get(day1))

            // 只加时长，字数 / 页数保持
            dao.addDelta(day1, durationMs = 500, chars = 0, pages = 0)
            assertEquals(DailyReadingEntity(day1, 4_000, 15, 3), dao.get(day1))
        } finally {
            cleanUp(dao)
        }
    }

    @Test
    fun differentDaysGetSeparateRows() = runBlocking {
        val dao = ArkDatabase.getInstance(RuntimeEnvironment.getApplication()).readingStatsDao()
        try {
            dao.addDelta(day1, 100, 1, 0)
            dao.addDelta(day2, 200, 0, 2)
            assertEquals(DailyReadingEntity(day1, 100, 1, 0), dao.get(day1))
            assertEquals(DailyReadingEntity(day2, 200, 0, 2), dao.get(day2))
        } finally {
            cleanUp(dao)
        }
    }

    @Test
    fun observeAllIsSortedByDateAscending() = runBlocking {
        val dao = ArkDatabase.getInstance(RuntimeEnvironment.getApplication()).readingStatsDao()
        try {
            // 故意乱序写入
            dao.addDelta(day3, 3, 0, 0)
            dao.addDelta(day1, 1, 0, 0)
            dao.addDelta(day2, 2, 0, 0)
            val mine = dao.observeAll().first().filter { it.date.startsWith("1999-") }
            assertEquals(listOf(day1, day2, day3), mine.map { it.date })
            assertEquals(listOf(day1, day2, day3), dao.getAll().filter { it.date.startsWith("1999-") }.map { it.date })
            assertEquals(2L, dao.observe(day2).first()?.durationMs)
        } finally {
            cleanUp(dao)
        }
    }

    @Test
    fun getReturnsNullForUnknownDayAndDeleteRemovesRow() = runBlocking {
        val dao = ArkDatabase.getInstance(RuntimeEnvironment.getApplication()).readingStatsDao()
        try {
            assertNull(dao.get(day1))
            assertNull(dao.observe(day1).first())
            dao.addDelta(day1, 1, 1, 1)
            dao.deleteByDate(day1)
            assertNull(dao.get(day1))
        } finally {
            cleanUp(dao)
        }
    }
}
