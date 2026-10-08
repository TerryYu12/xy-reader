package com.xyreader.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.xyreader.core.BookEntity
import com.xyreader.core.BookGroupEntity
import com.xyreader.core.BookmarkEntity
import com.xyreader.core.DailyReadingEntity
import com.xyreader.core.GoogleDriveAccountEntity
import com.xyreader.core.LocalRepoEntity
import com.xyreader.core.WebDavConfigEntity

/** 应用唯一 Room 数据库 */
@Database(
    entities = [
        BookEntity::class,
        BookGroupEntity::class,
        BookmarkEntity::class,
        WebDavConfigEntity::class,
        GoogleDriveAccountEntity::class,
        LocalRepoEntity::class,
        DailyReadingEntity::class,
    ],
    version = 8,
    exportSchema = false,
)
abstract class ArkDatabase : RoomDatabase() {

    abstract fun bookDao(): BookDao

    abstract fun groupDao(): GroupDao

    abstract fun localRepoDao(): LocalRepoDao

    abstract fun webDavDao(): WebDavDao

    abstract fun gdriveDao(): GdriveDao

    abstract fun readingStatsDao(): ReadingStatsDao

    companion object {
        private const val DB_NAME = "ark_reader.db"

        /**
         * v1 → v2：新增 WebDAV 配置表。
         * 列名/类型与非空性与 core.WebDavConfigEntity 字段严格一致，
         * 否则 Room 启动时会做 schema 校验并抛 IllegalStateException。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS webdav_configs (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "baseUrl TEXT NOT NULL, " +
                        "username TEXT NOT NULL, " +
                        "password TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v2 → v3：新增 Google Drive 账号表。
         * 列名/类型与非空性与 core.GoogleDriveAccountEntity 字段严格一致，
         * 否则 Room 启动时会做 schema 校验并抛 IllegalStateException。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS gdrive_accounts (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "clientId TEXT NOT NULL, " +
                        "clientSecret TEXT NOT NULL, " +
                        "refreshToken TEXT NOT NULL, " +
                        "folderId TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
            }
        }

        /**
         * v3 → v4：新增书架分组表 + books 表加 groupId 列。
         * 列名/类型与非空性与 core.BookGroupEntity 字段严格一致，
         * 否则 Room 启动时会做 schema 校验并抛 IllegalStateException。
         *
         * 注意：groupId 对应 Kotlin 可空 Long?，Room 期望该列可空且无默认值，
         * 因此 ALTER TABLE 只写 "INTEGER"：SQLite 的 ADD COLUMN 缺省即 NULL，
         * 存量行 groupId 全为 NULL（= 未分组）；若显式写 DEFAULT，
         * 会与 Room 期望的 schema（无 defaultValue）不一致，可能触发校验失败。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS book_groups (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE books ADD COLUMN groupId INTEGER")
            }
        }

        /**
         * v4 → v5：新增本地仓库表 + books 表加 localRepoId 列。
         * 列名/类型与非空性与 core.LocalRepoEntity 字段严格一致，
         * 否则 Room 启动时会做 schema 校验并抛 IllegalStateException。
         *
         * 注意：localRepoId 对应 Kotlin 可空 Long?，Room 期望该列可空且无默认值，
         * 因此 ALTER TABLE 只写 "INTEGER"：SQLite 的 ADD COLUMN 缺省即 NULL，
         * 存量行 localRepoId 全为 NULL（= 未绑定仓库）；若显式写 DEFAULT，
         * 会与 Room 期望的 schema（无 defaultValue）不一致，可能触发校验失败。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS local_repos (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "uri TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, " +
                        "coverFileName TEXT NOT NULL, " +
                        "defaultGroupId INTEGER, " +
                        "createdAt INTEGER NOT NULL)",
                )
                db.execSQL("ALTER TABLE books ADD COLUMN localRepoId INTEGER")
            }
        }

        /** v5 → v6：为分组添加可空自定义封面路径，不改写任何已有分组或书籍数据。 */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE book_groups ADD COLUMN coverPath TEXT")
            }
        }

        /**
         * v6 → v7：本地仓库新增「同文件夹 PDF 合并」开关。
         * DEFAULT 1 与实体 @ColumnInfo(defaultValue = "1") 一致（Room 启动时会校验默认值），
         * 已有仓库升级后默认开启；NOT NULL 对应 Kotlin 非空 Boolean。
         */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE local_repos ADD COLUMN mergeFolderPdfs INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * v7 → v8：新增每日阅读统计表 daily_reading。
         * 列名/类型/非空性与 core.DailyReadingEntity 严格一致（Kotlin 非空类型 → NOT NULL，
         * 实体字段的 Kotlin 默认值不进 schema，故 DDL 不写 DEFAULT），否则 Room 启动校验会失败。
         */
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS daily_reading (" +
                        "date TEXT NOT NULL, " +
                        "durationMs INTEGER NOT NULL, " +
                        "charsRead INTEGER NOT NULL, " +
                        "pagesRead INTEGER NOT NULL, " +
                        "PRIMARY KEY(date))",
                )
            }
        }

        @Volatile
        private var instance: ArkDatabase? = null

        /** 全局单例（双检锁），其他层统一从这里取数据库 */
        fun getInstance(context: Context): ArkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ArkDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                    )
                    .build()
                    .also { instance = it }
            }
    }
}
