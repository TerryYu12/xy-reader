package com.xyreader.data

import android.app.Application
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GroupCoverMigrationTest {

    @Test
    fun migrationAddsNullableCoverWithoutChangingGroupsOrBookAssignments() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "group-cover-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(5) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                "CREATE TABLE book_groups (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "name TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                            )
                            db.execSQL(
                                "CREATE TABLE books (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "title TEXT NOT NULL, groupId INTEGER)",
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO book_groups (id, name, createdAt) VALUES (7, '待读', 1234)")
            db.execSQL("INSERT INTO books (id, title, groupId) VALUES (41, '现有书', 7)")

            ArkDatabase.MIGRATION_5_6.migrate(db)

            db.query("SELECT id, name, createdAt, coverPath FROM book_groups WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(7L, cursor.getLong(0))
                assertEquals("待读", cursor.getString(1))
                assertEquals(1234L, cursor.getLong(2))
                assertNull(cursor.getString(3))
                assertFalse(cursor.moveToNext())
            }
            db.query("SELECT id, title, groupId FROM books WHERE id = 41").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(41L, cursor.getLong(0))
                assertEquals("现有书", cursor.getString(1))
                assertEquals(7L, cursor.getLong(2))
            }

            var coverColumnIsNullable = false
            db.query("PRAGMA table_info(book_groups)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "coverPath") {
                        coverColumnIsNullable = cursor.getInt(notNullIndex) == 0
                    }
                }
            }
            assertTrue(coverColumnIsNullable)
        } finally {
            helper.close()
        }
    }
}
