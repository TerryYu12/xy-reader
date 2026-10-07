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
 * v6 → v7 迁移：local_repos 新增「同文件夹 PDF 合并」开关列。
 * 升级前就存在的仓库默认开启，原有列值（含可空的 defaultGroupId）一字不动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RepoPdfMergeMigrationTest {

    @Test
    fun migrationAddsMergeFlagDefaultingOnWithoutChangingExistingRepos() {
        val context = RuntimeEnvironment.getApplication()
        val databaseName = "repo-pdf-merge-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(6) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            // v6 的 local_repos：列定义照抄 MIGRATION_4_5（此时还没有 mergeFolderPdfs）
                            db.execSQL(
                                "CREATE TABLE local_repos (" +
                                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                                    "name TEXT NOT NULL, " +
                                    "uri TEXT NOT NULL, " +
                                    "enabled INTEGER NOT NULL, " +
                                    "coverFileName TEXT NOT NULL, " +
                                    "defaultGroupId INTEGER, " +
                                    "createdAt INTEGER NOT NULL)",
                            )
                        }

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
            db.execSQL(
                "INSERT INTO local_repos (id, name, uri, enabled, coverFileName, defaultGroupId, createdAt) " +
                    "VALUES (3, '漫画库', 'content://tree/primary%3AComics', 1, 'cover', 7, 1234)",
            )
            db.execSQL(
                "INSERT INTO local_repos (id, name, uri, enabled, coverFileName, defaultGroupId, createdAt) " +
                    "VALUES (4, '停用仓库', 'content://tree/primary%3AOld', 0, '', NULL, 5678)",
            )

            ArkDatabase.MIGRATION_6_7.migrate(db)

            db.query(
                "SELECT id, name, uri, enabled, coverFileName, defaultGroupId, createdAt, mergeFolderPdfs " +
                    "FROM local_repos WHERE id = 3",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(3L, cursor.getLong(0))
                assertEquals("漫画库", cursor.getString(1))
                assertEquals("content://tree/primary%3AComics", cursor.getString(2))
                assertEquals(1, cursor.getInt(3))
                assertEquals("cover", cursor.getString(4))
                assertEquals(7L, cursor.getLong(5))
                assertEquals(1234L, cursor.getLong(6))
                assertEquals(1, cursor.getInt(7))
                assertFalse(cursor.moveToNext())
            }
            // 停用状态与空的默认分组同样原样保留，且也默认开启合并
            db.query(
                "SELECT enabled, defaultGroupId, mergeFolderPdfs FROM local_repos WHERE id = 4",
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertTrue(cursor.isNull(1))
                assertEquals(1, cursor.getInt(2))
            }

            // 列定义要与实体 @ColumnInfo(defaultValue = "1") 对上，否则 Room 启动校验会失败
            var mergeColumnFound = false
            db.query("PRAGMA table_info(local_repos)").use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow("name")
                val notNullIndex = cursor.getColumnIndexOrThrow("notnull")
                val defaultIndex = cursor.getColumnIndexOrThrow("dflt_value")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == "mergeFolderPdfs") {
                        mergeColumnFound = true
                        assertEquals(1, cursor.getInt(notNullIndex))
                        assertEquals("1", cursor.getString(defaultIndex))
                    }
                }
            }
            assertTrue(mergeColumnFound)
        } finally {
            helper.close()
        }
    }
}
