package com.xyreader.data

import android.app.Application
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v7 → v8 迁移：新增 daily_reading 表。
 * 列的类型 / 非空 / 主键要与 DailyReadingEntity 对上，否则 Room 启动校验会失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReadingStatsMigrationTest {

    @Test
    fun migrationCreatesDailyReadingTableMatchingTheEntity() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "reading-stats-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(7) {
                        // v7 还没有 daily_reading 表；其他表与本迁移无关，不必创建
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase

            ArkDatabase.MIGRATION_7_8.migrate(db)

            data class Column(val type: String, val notNull: Int, val pk: Int)
            val columns = mutableMapOf<String, Column>()
            db.query("PRAGMA table_info(daily_reading)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val typeIndex = cursor.getColumnIndexOrThrow("type")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val pkIndex = cursor.getColumnIndexOrThrow("pk")
                while (cursor.moveToNext()) {
                    columns[cursor.getString(nameIndex)] = Column(
                        type = cursor.getString(typeIndex),
                        notNull = cursor.getInt(notNullIndex),
                        pk = cursor.getInt(pkIndex),
                    )
                }
            }
            assertEquals(
                mapOf(
                    "date" to Column("TEXT", 1, 1),
                    "durationMs" to Column("INTEGER", 1, 0),
                    "charsRead" to Column("INTEGER", 1, 0),
                    "pagesRead" to Column("INTEGER", 1, 0),
                ),
                columns,
            )

            // 能插入并读回
            db.execSQL("INSERT INTO daily_reading (date, durationMs, charsRead, pagesRead) VALUES ('2026-10-08', 300000, 1234, 5)")
            db.query("SELECT date, durationMs, charsRead, pagesRead FROM daily_reading").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("2026-10-08", cursor.getString(0))
                assertEquals(300_000L, cursor.getLong(1))
                assertEquals(1234L, cursor.getLong(2))
                assertEquals(5, cursor.getInt(3))
                assertFalse(cursor.moveToNext())
            }

            // 主键唯一：同一天重复插入必须报错（累加靠 IGNORE 占位 + UPDATE，而非重复行）
            var duplicateRejected = false
            try {
                db.execSQL("INSERT INTO daily_reading (date, durationMs, charsRead, pagesRead) VALUES ('2026-10-08', 1, 1, 1)")
            } catch (_: Exception) {
                duplicateRejected = true
            }
            assertTrue(duplicateRejected)

            // IF NOT EXISTS：重复执行迁移不会清空已有数据
            ArkDatabase.MIGRATION_7_8.migrate(db)
            db.query("SELECT COUNT(*) FROM daily_reading").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            helper.close()
        }
    }
}
