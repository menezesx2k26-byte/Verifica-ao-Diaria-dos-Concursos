package com.menezes.concursoswatch.data

import android.content.Context
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.SourceHealth
import com.menezes.concursoswatch.model.SyncResult

class ContestRepository(context: Context) {
    private val db = AppDatabase(context.applicationContext)
    private val remote = RemoteDataSource()

    fun contests(): List<Contest> = db.contests()
    fun alerts(): List<AlertItem> = db.alerts()
    fun sourceHealthJson(): String = db.meta("source_health") ?: "[]"
    fun lastSync(): Long = db.meta("last_sync")?.toLongOrNull() ?: 0L
    fun lastContestError(): String? = db.meta("contest_error")?.takeIf { it.isNotBlank() }
    fun lastAlertError(): String? = db.meta("alert_error")?.takeIf { it.isNotBlank() }
    fun latestKnownRelease(): String? = db.meta("latest_release")

    fun syncAll(): SyncResult {
        val contestBaseline = db.meta("contest_baseline") == null
        val alertBaseline = db.meta("alert_baseline") == null
        val newContestIds = mutableListOf<String>()
        val newAlertIds = mutableListOf<Int>()
        var contestsOk = false
        var alertsOk = false
        var contestError: String? = null
        var alertError: String? = null
        var health: List<SourceHealth> = emptyList()

        try {
            val feed = remote.fetchContestFeed()
            health = feed.health
            for (item in feed.items) {
                val inserted = db.upsertContest(item, unreadIfNew = !contestBaseline)
                if (inserted && !contestBaseline) newContestIds += item.id
            }
            db.setMeta("contest_baseline", "1")
            db.setMeta("contest_error", "")
            db.setMeta("source_health", sourceHealthToJson(feed.health))
            db.setMeta("source_count", feed.sourceCount.toString())
            db.setMeta("feed_updated_at", feed.updatedAt)
            contestsOk = true
        } catch (t: Throwable) {
            contestError = t.message ?: t.javaClass.simpleName
            db.setMeta("contest_error", contestError)
        }

        try {
            for (item in remote.fetchAlerts()) {
                val inserted = db.upsertAlert(item, unreadIfNew = !alertBaseline)
                if (inserted && !alertBaseline) newAlertIds += item.id
            }
            db.setMeta("alert_baseline", "1")
            db.setMeta("alert_error", "")
            alertsOk = true
        } catch (t: Throwable) {
            alertError = t.message ?: t.javaClass.simpleName
            db.setMeta("alert_error", alertError)
        }

        runCatching { remote.latestReleaseVersion() }.getOrNull()?.let { db.setMeta("latest_release", it) }
        if (contestsOk || alertsOk) db.setMeta("last_sync", System.currentTimeMillis().toString())
        return SyncResult(contestsOk, alertsOk, contestError, alertError, newContestIds, newAlertIds, health)
    }

    fun setFavorite(id: String, value: Boolean) = db.setFavorite(id, value)
    fun markContestRead(id: String) = db.markContestRead(id)
    fun markAlertRead(id: Int) = db.markAlertRead(id)
    fun markAllContestsRead() = db.markAllContestsRead()
    fun markAllAlertsRead() = db.markAllAlertsRead()
    fun sourceCount(): Int = db.meta("source_count")?.toIntOrNull() ?: 0

    private fun sourceHealthToJson(items: List<SourceHealth>): String = buildString {
        append('[')
        items.forEachIndexed { index, s ->
            if (index > 0) append(',')
            append("{\"id\":\"").append(escape(s.id)).append("\",\"label\":\"").append(escape(s.label))
                .append("\",\"ok\":").append(s.ok).append(",\"item_count\":").append(s.itemCount)
                .append(",\"checked_at\":\"").append(escape(s.checkedAt)).append("\",\"error\":\"").append(escape(s.error)).append("\"}")
        }
        append(']')
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}
