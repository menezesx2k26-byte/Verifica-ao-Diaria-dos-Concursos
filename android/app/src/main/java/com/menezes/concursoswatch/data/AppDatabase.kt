package com.menezes.concursoswatch.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ContestEntity::class, AlertEntity::class, SourceHealthEntity::class, MetaEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun contestDao(): ContestDao
    abstract fun alertDao(): AlertDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun metaDao(): MetaDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contests RENAME TO contests_old")
                db.execSQL("ALTER TABLE alerts RENAME TO alerts_old")
                db.execSQL("ALTER TABLE meta RENAME TO meta_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS contests (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        organization TEXT NOT NULL,
                        city TEXT NOT NULL,
                        uf TEXT NOT NULL,
                        region TEXT NOT NULL,
                        scope TEXT NOT NULL,
                        type TEXT NOT NULL,
                        education TEXT NOT NULL,
                        area TEXT NOT NULL,
                        remuneration TEXT NOT NULL,
                        vacancies TEXT NOT NULL,
                        fee TEXT NOT NULL,
                        start_date TEXT NOT NULL,
                        end_date TEXT NOT NULL,
                        status TEXT NOT NULL,
                        source TEXT NOT NULL,
                        url TEXT NOT NULL,
                        edital_url TEXT NOT NULL,
                        first_seen TEXT NOT NULL,
                        last_seen TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        favorite INTEGER NOT NULL,
                        unread INTEGER NOT NULL,
                        active INTEGER NOT NULL,
                        sync_generation INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO contests
                    SELECT id, title, COALESCE(organization,''), COALESCE(city,''), COALESCE(uf,''),
                           COALESCE(region,''), COALESCE(scope,''), COALESCE(type,''), COALESCE(education,''),
                           COALESCE(area,''), COALESCE(remuneration,''), COALESCE(vacancies,''), COALESCE(fee,''),
                           COALESCE(start_date,''), COALESCE(end_date,''), COALESCE(status,''), COALESCE(source,''),
                           COALESCE(url,''), COALESCE(edital_url,''), COALESCE(first_seen,''), COALESCE(last_seen,''),
                           priority, favorite, unread, 1, 0
                    FROM contests_old
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS alerts (
                        id INTEGER NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        body TEXT NOT NULL,
                        url TEXT NOT NULL,
                        created_at TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        unread INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO alerts
                    SELECT id, title, COALESCE(body,''), COALESCE(url,''), COALESCE(created_at,''), priority, unread
                    FROM alerts_old
                """.trimIndent())

                db.execSQL("CREATE TABLE IF NOT EXISTS meta (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                db.execSQL("INSERT INTO meta SELECT `key`, COALESCE(value,'') FROM meta_old")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS source_health (
                        id TEXT NOT NULL PRIMARY KEY,
                        label TEXT NOT NULL,
                        http_ok INTEGER NOT NULL,
                        parser_ok INTEGER NOT NULL,
                        semantic_ok INTEGER NOT NULL,
                        item_count INTEGER NOT NULL,
                        expected_min INTEGER NOT NULL,
                        checked_at TEXT NOT NULL,
                        last_success_at TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        error TEXT NOT NULL
                    )
                """.trimIndent())

                db.execSQL("DROP TABLE contests_old")
                db.execSQL("DROP TABLE alerts_old")
                db.execSQL("DROP TABLE meta_old")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "concursos_watch.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
