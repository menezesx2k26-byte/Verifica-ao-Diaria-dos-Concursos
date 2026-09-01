package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardManifest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRemoteDataSourceTest {
    private val source = DashboardRemoteDataSource(
        apiBaseOverride = "https://concursos-watch-v4.example.workers.dev",
    )

    @Test(expected = IllegalArgumentException::class)
    fun rejectsManifestFromUnexpectedHost() {
        source.validateManifestUrls(
            fixtureManifest(htmlUrl = "https://evil.example/dashboard"),
        )
    }

    @Test
    fun acceptsOnlyExpectedMimeTypes() {
        assertTrue(source.isAllowedMime("text/html; charset=utf-8", AssetKind.HTML))
        assertTrue(source.isAllowedMime("text/css; charset=utf-8", AssetKind.CSS))
        assertTrue(source.isAllowedMime("application/json; charset=utf-8", AssetKind.MANIFEST))
        assertFalse(source.isAllowedMime("application/octet-stream", AssetKind.HTML))
        assertFalse(source.isAllowedMime("text/html", AssetKind.CSS))
    }

    @Test
    fun manifestHeadersCarryEtagWithoutOtherCapabilities() {
        val headers = source.manifestRequestHeaders("\"v7\"")
        assertTrue(headers["If-None-Match"] == "\"v7\"")
        assertTrue(headers["Accept"] == "application/json")
        assertFalse(headers.containsKey("Authorization"))
    }

    private fun fixtureManifest(
        htmlUrl: String = "https://concursos-watch-v4.example.workers.dev/dashboard",
        cssUrl: String = "https://concursos-watch-v4.example.workers.dev/assets/dashboard.css?v=1",
    ) = DashboardManifest(
        schemaVersion = 1,
        dashboardVersion = 1,
        styleVersion = 1,
        minAppVersion = "4.0.0",
        publishedAt = "2026-08-24T00:00:00Z",
        htmlUrl = htmlUrl,
        cssUrl = cssUrl,
        htmlSha256 = "11".repeat(32),
        cssSha256 = "22".repeat(32),
        etag = "\"v1\"",
    )
}
