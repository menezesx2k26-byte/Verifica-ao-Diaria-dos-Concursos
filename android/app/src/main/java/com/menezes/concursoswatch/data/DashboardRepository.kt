package com.menezes.concursoswatch.data

sealed interface DashboardUiState {
    data object Loading : DashboardUiState
    data class Ready(
        val dashboard: StoredDashboard,
        val stale: Boolean,
    ) : DashboardUiState
    data class Unavailable(val reason: String) : DashboardUiState
}

class DashboardRepository(
    private val remote: DashboardRemoteClient,
    private val store: DashboardStore,
) {
    fun cachedState(): DashboardUiState = store.current()?.let {
        DashboardUiState.Ready(it, stale = true)
    } ?: DashboardUiState.Loading

    suspend fun refresh(): DashboardUiState {
        val cached = store.current()
        return try {
            when (val result = remote.fetchManifest(cached?.etag)) {
                DashboardManifestResult.NotModified -> {
                    if (cached != null) {
                        DashboardUiState.Ready(cached, stale = false)
                    } else {
                        DashboardUiState.Unavailable("manifest_not_modified_without_cache")
                    }
                }
                is DashboardManifestResult.Modified -> {
                    val bundle = remote.fetchBundle(result.manifest)
                    val promoted = store.promote(bundle)
                    DashboardUiState.Ready(promoted, stale = false)
                }
            }
        } catch (error: Throwable) {
            cached?.let { DashboardUiState.Ready(it, stale = true) }
                ?: DashboardUiState.Unavailable(error.message ?: "dashboard_unavailable")
        }
    }
}
