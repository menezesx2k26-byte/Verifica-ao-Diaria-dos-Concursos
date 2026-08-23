package com.menezes.concursoswatch.data

import android.content.Context
import androidx.room.withTransaction
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.SourceHealth
import com.menezes.concursoswatch.model.SyncResult

class ContestRepository(context: Context) {
    private val db = AppDatabase.get(context.applicationContext)
    private val remote = RemoteDataSource()

    suspend fun contests(): List<Contest> = db.contestDao().visible().map { it.toModel() }
    suspend fun contest(id: String): Contest? = db.contestDao().byId(id)?.toModel()
    suspend fun alerts(): List<AlertItem> = db.alertDao().all().map { it.toModel() }
    suspend fun sourceHealth(): List<SourceHealth> = db.sourceHealthDao().all().map { it.toModel() }
    suspend fun lastSync(): Long = meta("last_sync")?.toLongOrNull() ?: 0L
    suspend fun lastContestError(): String? = meta("contest_error")?.takeIf { it.isNotBlank() }
    suspend fun lastAlertError(): String? = meta("alert_error")?.takeIf { it.isNotBlank() }
    suspend fun latestKnownRelease(): String? = meta("latest_release")
    suspend fun sourceCount(): Int = meta("source_count")?.toIntOrNull() ?: 0

    suspend fun syncAll(): SyncResult {
        val contestBaseline = meta("contest_baseline") == null
        val alertBaseline = meta("alert_baseline") == null
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
            val generation = System.currentTimeMillis()
            val existing = db.contestDao().all().associateBy { it.id }
            val incoming = feed.items.map { item ->
                val old = existing[item.id]
                if (old == null && !contestBaseline) newContestIds += item.id
                item.toEntity(old?.favorite ?: false, old?.unread ?: (!contestBaseline), true, generation)
            }
            db.withTransaction {
                db.contestDao().upsert(incoming)
                db.contestDao().archiveMissing(generation)
                db.sourceHealthDao().upsert(feed.health.map { it.toEntity() })
                putMeta("contest_baseline", "1")
                putMeta("contest_error", "")
                putMeta("source_count", feed.sourceCount.toString())
                putMeta("feed_updated_at", feed.updatedAt)
            }
            contestsOk = true
        } catch (t: Throwable) {
            contestError = t.message ?: t.javaClass.simpleName
            putMeta("contest_error", contestError)
        }

        try {
            val remoteAlerts = remote.fetchAlerts()
            val existing = db.alertDao().all().associateBy { it.id }
            val incoming = remoteAlerts.map { item ->
                val old = existing[item.id]
                if (old == null && !alertBaseline) newAlertIds += item.id
                item.toEntity(old?.unread ?: (!alertBaseline))
            }
            db.withTransaction {
                db.alertDao().upsert(incoming)
                putMeta("alert_baseline", "1")
                putMeta("alert_error", "")
            }
            alertsOk = true
        } catch (t: Throwable) {
            alertError = t.message ?: t.javaClass.simpleName
            putMeta("alert_error", alertError)
        }

        runCatching { remote.latestReleaseVersion() }.getOrNull()?.let { putMeta("latest_release", it) }
        if (contestsOk || alertsOk) putMeta("last_sync", System.currentTimeMillis().toString())
        return SyncResult(contestsOk, alertsOk, contestError, alertError, newContestIds, newAlertIds, health)
    }

    suspend fun setFavorite(id: String, value: Boolean) = db.contestDao().setFavorite(id, value)
    suspend fun markContestRead(id: String) = db.contestDao().markRead(id)
    suspend fun markAlertRead(id: Int) = db.alertDao().markRead(id)
    suspend fun markAllContestsRead() = db.contestDao().markAllRead()
    suspend fun markAllAlertsRead() = db.alertDao().markAllRead()

    private suspend fun meta(key: String): String? = db.metaDao().get(key)
    private suspend fun putMeta(key: String, value: String) = db.metaDao().put(MetaEntity(key, value))
}

private fun ContestEntity.toModel() = Contest(
    id, title, organization, city, uf, region, scope, type, education, area, remuneration,
    vacancies, fee, startDate, endDate, status, source, url, editalUrl, firstSeen, lastSeen,
    priority, favorite, unread, active,
)

private fun Contest.toEntity(favorite: Boolean, unread: Boolean, active: Boolean, generation: Long) = ContestEntity(
    id, title, organization, city, uf, region, scope, type, education, area, remuneration,
    vacancies, fee, startDate, endDate, status, source, url, editalUrl, firstSeen, lastSeen,
    priority, favorite, unread, active, generation,
)

private fun AlertEntity.toModel() = AlertItem(id, title, body, url, createdAt, priority, unread)
private fun AlertItem.toEntity(unread: Boolean) = AlertEntity(id, title, body, url, createdAt, priority, unread)

private fun SourceHealthEntity.toModel() = SourceHealth(
    id, label, httpOk, parserOk, semanticOk, itemCount, expectedMin, checkedAt, lastSuccessAt, fingerprint, scanStatus, error,
)
private fun SourceHealth.toEntity() = SourceHealthEntity(
    id, label, httpOk, parserOk, semanticOk, itemCount, expectedMin, checkedAt, lastSuccessAt, fingerprint, scanStatus, error,
)
