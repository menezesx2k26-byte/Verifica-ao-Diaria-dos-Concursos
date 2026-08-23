package com.menezes.concursoswatch.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.menezes.concursoswatch.data.ContestRepository
import com.menezes.concursoswatch.data.SettingsStore
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.RegionFilter
import com.menezes.concursoswatch.model.SourceHealth
import com.menezes.concursoswatch.model.StatusFilter
import com.menezes.concursoswatch.model.UserSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AppUiState(
    val contests: List<Contest> = emptyList(), val alerts: List<AlertItem> = emptyList(),
    val sourceHealth: List<SourceHealth> = emptyList(), val regionFilter: RegionFilter = RegionFilter.ALL,
    val statusFilter: StatusFilter = StatusFilter.ALL, val search: String = "", val syncing: Boolean = false,
    val lastSync: Long = 0L, val contestError: String? = null, val alertError: String? = null,
    val sourceCount: Int = 0, val healthySources: Int = 0, val settings: UserSettings = UserSettings(),
    val latestRelease: String? = null, val selectedContest: Contest? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ContestRepository(app)
    private val settingsStore = SettingsStore(app)
    var state by mutableStateOf(AppUiState()); private set

    init {
        reload()
        viewModelScope.launch { settingsStore.flow.collectLatest { state = state.copy(settings = it) } }
    }

    fun reload() {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val health = repo.sourceHealth()
                state.copy(
                    contests = repo.contests(), alerts = repo.alerts(), sourceHealth = health,
                    lastSync = repo.lastSync(), contestError = repo.lastContestError(), alertError = repo.lastAlertError(),
                    sourceCount = repo.sourceCount(), healthySources = health.count { it.ok }, latestRelease = repo.latestKnownRelease(),
                )
            }
            state = loaded
        }
    }

    fun syncNow() {
        if (state.syncing) return
        state = state.copy(syncing = true)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.syncAll() }
            state = state.copy(syncing = false)
            reload()
        }
    }

    fun setSearch(value: String) { state = state.copy(search = value) }
    fun setRegion(value: RegionFilter) { state = state.copy(regionFilter = value) }
    fun setStatus(value: StatusFilter) { state = state.copy(statusFilter = value) }

    fun loadContest(id: String) {
        viewModelScope.launch {
            val selected = withContext(Dispatchers.IO) { repo.markContestRead(id); repo.contest(id)?.copy(unread = false) }
            state = state.copy(selectedContest = selected)
            reload()
        }
    }

    fun markAlertRead(a: AlertItem) = viewModelScope.launch { withContext(Dispatchers.IO) { repo.markAlertRead(a.id) }; reload() }
    fun toggleFavorite(c: Contest) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.setFavorite(c.id, !c.favorite) }
        state = state.copy(selectedContest = state.selectedContest?.takeIf { it.id == c.id }?.copy(favorite = !c.favorite) ?: state.selectedContest)
        reload()
    }
    fun markAllContestsRead() = viewModelScope.launch { withContext(Dispatchers.IO) { repo.markAllContestsRead() }; reload() }
    fun markAllAlertsRead() = viewModelScope.launch { withContext(Dispatchers.IO) { repo.markAllAlertsRead() }; reload() }
    fun saveSettings(s: UserSettings) { viewModelScope.launch { settingsStore.save(s) } }

    fun filteredContests(favoritesOnly: Boolean = false): List<Contest> {
        val q = state.search.trim().lowercase(); val today = LocalDate.now()
        return state.contests.filter { c ->
            if (favoritesOnly && !c.favorite) return@filter false
            if (!favoritesOnly && !c.active) return@filter false
            val regionOk = when (state.regionFilter) {
                RegionFilter.ALL -> true
                RegionFilter.FEDERAL -> c.scope.equals("federal", true)
                RegionFilter.SC -> c.region.equals("SC", true) || c.uf.equals("SC", true)
                RegionFilter.SUL -> c.region.equals("Sul", true) || c.uf.uppercase() in setOf("SC", "PR", "RS")
                RegionFilter.BAIXADA -> c.city.lowercase() in setOf("praia grande", "santos", "são vicente", "sao vicente", "cubatão", "cubatao", "guarujá", "guaruja")
            }
            val statusOk = when (state.statusFilter) {
                StatusFilter.ALL -> true
                StatusFilter.OPEN -> c.status in setOf("open", "closing_soon")
                StatusFilter.NEW -> c.unread
                StatusFilter.CLOSING_SOON -> parseDate(c.endDate)?.let { ChronoUnit.DAYS.between(today, it) in 0..7 } ?: false
            }
            val searchOk = q.isBlank() || listOf(c.title,c.organization,c.city,c.area,c.education,c.type,c.source).any { it.lowercase().contains(q) }
            regionOk && statusOk && searchOk
        }
    }

    private fun parseDate(value: String): LocalDate? = runCatching {
        when {
            value.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> LocalDate.parse(value)
            value.matches(Regex("\\d{2}/\\d{2}/\\d{4}")) -> value.split('/').let { LocalDate.of(it[2].toInt(), it[1].toInt(), it[0].toInt()) }
            else -> null
        }
    }.getOrNull()

    fun relativeSync(): String {
        if (state.lastSync == 0L) return "Nunca sincronizado"
        val minutes = ChronoUnit.MINUTES.between(Instant.ofEpochMilli(state.lastSync), Instant.now())
        return when { minutes < 1 -> "agora"; minutes < 60 -> "há ${minutes} min"; else -> "há ${minutes / 60} h" }
    }
}
