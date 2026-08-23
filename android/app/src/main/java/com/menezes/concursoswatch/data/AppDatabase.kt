package com.menezes.concursoswatch.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest

class AppDatabase(context: Context) : SQLiteOpenHelper(context, "concursos_watch.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE contests(
                id TEXT PRIMARY KEY, title TEXT NOT NULL, organization TEXT, city TEXT, uf TEXT,
                region TEXT, scope TEXT, type TEXT, education TEXT, area TEXT, remuneration TEXT,
                vacancies TEXT, fee TEXT, start_date TEXT, end_date TEXT, status TEXT, source TEXT,
                url TEXT, edital_url TEXT, first_seen TEXT, last_seen TEXT, priority INTEGER NOT NULL,
                favorite INTEGER NOT NULL DEFAULT 0, unread INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE alerts(
                id INTEGER PRIMARY KEY, title TEXT NOT NULL, body TEXT, url TEXT, created_at TEXT,
                priority INTEGER NOT NULL, unread INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE INDEX idx_contests_first_seen ON contests(first_seen DESC)")
        db.execSQL("CREATE INDEX idx_contests_status ON contests(status)")
        db.execSQL("CREATE INDEX idx_alerts_created ON alerts(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun hasContest(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM contests WHERE id=? LIMIT 1", arrayOf(id)).use { it.moveToFirst() }
    fun hasAlert(id: Int): Boolean = readableDatabase.rawQuery("SELECT 1 FROM alerts WHERE id=? LIMIT 1", arrayOf(id.toString())).use { it.moveToFirst() }

    fun upsertContest(item: Contest, unreadIfNew: Boolean): Boolean {
        val existed = hasContest(item.id)
        val existing = if (existed) readableDatabase.rawQuery("SELECT favorite, unread FROM contests WHERE id=?", arrayOf(item.id)).use {
            if (it.moveToFirst()) Pair(it.getInt(0), it.getInt(1)) else Pair(0, 0)
        } else Pair(0, if (unreadIfNew) 1 else 0)
        val v = ContentValues().apply {
            put("id", item.id); put("title", item.title); put("organization", item.organization); put("city", item.city)
            put("uf", item.uf); put("region", item.region); put("scope", item.scope); put("type", item.type)
            put("education", item.education); put("area", item.area); put("remuneration", item.remuneration)
            put("vacancies", item.vacancies); put("fee", item.fee); put("start_date", item.startDate); put("end_date", item.endDate)
            put("status", item.status); put("source", item.source); put("url", item.url); put("edital_url", item.editalUrl)
            put("first_seen", item.firstSeen); put("last_seen", item.lastSeen); put("priority", item.priority)
            put("favorite", existing.first); put("unread", existing.second)
        }
        writableDatabase.insertWithOnConflict("contests", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        return !existed
    }

    fun upsertAlert(item: AlertItem, unreadIfNew: Boolean): Boolean {
        val existed = hasAlert(item.id)
        val unread = if (existed) readableDatabase.rawQuery("SELECT unread FROM alerts WHERE id=?", arrayOf(item.id.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } else if (unreadIfNew) 1 else 0
        val v = ContentValues().apply {
            put("id", item.id); put("title", item.title); put("body", item.body); put("url", item.url)
            put("created_at", item.createdAt); put("priority", item.priority); put("unread", unread)
        }
        writableDatabase.insertWithOnConflict("alerts", null, v, SQLiteDatabase.CONFLICT_REPLACE)
        return !existed
    }

    fun contests(): List<Contest> = readableDatabase.rawQuery("SELECT * FROM contests ORDER BY first_seen DESC, priority DESC", null).use { c ->
        buildList {
            while (c.moveToNext()) add(Contest(
                id=c.getString(c.getColumnIndexOrThrow("id")), title=c.getString(c.getColumnIndexOrThrow("title")),
                organization=c.s("organization"), city=c.s("city"), uf=c.s("uf"), region=c.s("region"), scope=c.s("scope"),
                type=c.s("type"), education=c.s("education"), area=c.s("area"), remuneration=c.s("remuneration"), vacancies=c.s("vacancies"),
                fee=c.s("fee"), startDate=c.s("start_date"), endDate=c.s("end_date"), status=c.s("status"), source=c.s("source"),
                url=c.s("url"), editalUrl=c.s("edital_url"), firstSeen=c.s("first_seen"), lastSeen=c.s("last_seen"),
                priority=c.getInt(c.getColumnIndexOrThrow("priority")), favorite=c.getInt(c.getColumnIndexOrThrow("favorite"))==1,
                unread=c.getInt(c.getColumnIndexOrThrow("unread"))==1
            ))
        }
    }

    fun alerts(): List<AlertItem> = readableDatabase.rawQuery("SELECT * FROM alerts ORDER BY created_at DESC, id DESC", null).use { c ->
        buildList {
            while (c.moveToNext()) add(AlertItem(
                id=c.getInt(c.getColumnIndexOrThrow("id")), title=c.s("title"), body=c.s("body"), url=c.s("url"),
                createdAt=c.s("created_at"), priority=c.getInt(c.getColumnIndexOrThrow("priority")), unread=c.getInt(c.getColumnIndexOrThrow("unread"))==1
            ))
        }
    }

    fun setFavorite(id: String, favorite: Boolean) = writableDatabase.execSQL("UPDATE contests SET favorite=? WHERE id=?", arrayOf(if(favorite)1 else 0, id))
    fun markContestRead(id: String) = writableDatabase.execSQL("UPDATE contests SET unread=0 WHERE id=?", arrayOf(id))
    fun markAlertRead(id: Int) = writableDatabase.execSQL("UPDATE alerts SET unread=0 WHERE id=?", arrayOf(id))
    fun markAllContestsRead() = writableDatabase.execSQL("UPDATE contests SET unread=0")
    fun markAllAlertsRead() = writableDatabase.execSQL("UPDATE alerts SET unread=0")

    fun meta(key: String): String? = readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getString(0) else null }
    fun setMeta(key: String, value: String) {
        writableDatabase.insertWithOnConflict("meta", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun android.database.Cursor.s(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
}
