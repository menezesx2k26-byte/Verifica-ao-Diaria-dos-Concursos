package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardBundle
import com.menezes.concursoswatch.model.DashboardManifest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DashboardRepositoryTest {
    @Test
    fun networkFailureKeepsLastKnownGood() = runBlocking {
        val store = newStore()
        store.promote(bundle(1))
        val remote = FakeDashboardRemote(error = IllegalStateException("offline"))
        val repository = DashboardRepository(remote, store)

        val result = repository.refresh()

        assertTrue(result is DashboardUiState.Ready)
        result as DashboardUiState.Ready
        assertEquals(1L, result.dashboard.dashboardVersion)
        assertTrue(result.stale)
    }

    @Test
    fun notModifiedUsesCachedWithoutFetchingBundle() = runBlocking {
        val store = newStore()
        store.promote(bundle(3))
        val remote = FakeDashboardRemote(manifestResult = DashboardManifestResult.NotModified)
        val repository = DashboardRepository(remote, store)

        val result = repository.refresh()

        assertTrue(result is DashboardUiState.Ready)
        result as DashboardUiState.Ready
        assertEquals(3L, result.dashboard.dashboardVersion)
        assertFalse(result.stale)
        assertEquals(0, remote.bundleFetches)
        assertEquals("\"v3\"", remote.lastEtag)
    }

    @Test
    fun validModifiedBundleIsPromoted() = runBlocking {
        val store = newStore()
        store.promote(bundle(1))
        val next = bundle(2)
        val remote = FakeDashboardRemote(
            manifestResult = DashboardManifestResult.Modified(next.manifest),
            bundle = next,
        )
        val repository = DashboardRepository(remote, store)

        val result = repository.refresh()

        assertTrue(result is DashboardUiState.Ready)
        result as DashboardUiState.Ready
        assertEquals(2L, result.dashboard.dashboardVersion)
        assertFalse(result.stale)
        assertEquals(2L, store.current()!!.dashboardVersion)
    }

    @Test
    fun noCacheAndNetworkFailureFallsBackToNative() = runBlocking {
        val store = newStore()
        val repository = DashboardRepository(
            FakeDashboardRemote(error = IllegalStateException("offline")),
            store,
        )
        assertTrue(repository.refresh() is DashboardUiState.Unavailable)
    }

    private fun newStore(): DashboardStore {
        val root = Files.createTempDirectory("dashboard-repository").toFile()
        return DashboardStore(
            root,
            DashboardValidator("concursos-watch-v4.example.workers.dev", "4.0.0-dev"),
        )
    }

    private fun bundle(version: Long): DashboardBundle {
        val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"/assets/dashboard.css?v=$version\"></head><body><main>v$version</main></body></html>".toByteArray()
        val css = "body{margin:$version}px".toByteArray()
        return DashboardBundle(
            DashboardManifest(
                1,
                version,
                version,
                "4.0.0",
                "2026-08-24T00:00:00Z",
                "https://concursos-watch-v4.example.workers.dev/dashboard",
                "https://concursos-watch-v4.example.workers.dev/assets/dashboard.css?v=$version",
                DashboardValidator.sha256Hex(html),
                DashboardValidator.sha256Hex(css),
                "\"v$version\"",
            ),
            html,
            css,
        )
    }

    private class FakeDashboardRemote(
        private val manifestResult: DashboardManifestResult? = null,
        private val bundle: DashboardBundle? = null,
        private val error: Throwable? = null,
    ) : DashboardRemoteClient {
        var bundleFetches = 0
        var lastEtag: String? = null

        override suspend fun fetchManifest(etag: String?): DashboardManifestResult {
            lastEtag = etag
            error?.let { throw it }
            return requireNotNull(manifestResult)
        }

        override suspend fun fetchBundle(manifest: DashboardManifest): DashboardBundle {
            bundleFetches += 1
            error?.let { throw it }
            return requireNotNull(bundle)
        }
    }
}
