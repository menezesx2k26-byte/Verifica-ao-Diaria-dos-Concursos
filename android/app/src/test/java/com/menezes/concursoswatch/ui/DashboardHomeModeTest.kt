package com.menezes.concursoswatch.ui

import com.menezes.concursoswatch.data.DashboardUiState
import com.menezes.concursoswatch.data.StoredDashboard
import com.menezes.concursoswatch.model.DashboardManifest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DashboardHomeModeTest {
    @Test
    fun readyDashboardUsesDynamicHome() {
        val stored = StoredDashboard(
            dashboardVersion = 1,
            styleVersion = 1,
            etag = "etag-1",
            rootDir = File("dashboard/current"),
            manifest = DashboardManifest(
                schemaVersion = 1,
                dashboardVersion = 1,
                styleVersion = 1,
                minAppVersion = "4.0.0",
                publishedAt = "2026-08-24T00:00:00Z",
                htmlUrl = "https://concursos-watch.example.workers.dev/dashboard",
                cssUrl = "https://concursos-watch.example.workers.dev/assets/dashboard.css",
                htmlSha256 = "00".repeat(32),
                cssSha256 = "11".repeat(32),
                etag = "etag-1",
            ),
        )

        assertEquals(
            DashboardHomeMode.DYNAMIC,
            dashboardHomeMode(DashboardUiState.Ready(stored, stale = false)),
        )
    }

    @Test
    fun loadingAndUnavailableUseNativeFallback() {
        assertEquals(DashboardHomeMode.FALLBACK, dashboardHomeMode(DashboardUiState.Loading))
        assertEquals(
            DashboardHomeMode.FALLBACK,
            dashboardHomeMode(DashboardUiState.Unavailable("offline")),
        )
    }
}
