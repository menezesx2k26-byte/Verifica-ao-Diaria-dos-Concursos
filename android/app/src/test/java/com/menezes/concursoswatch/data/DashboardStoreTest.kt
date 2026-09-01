package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardBundle
import com.menezes.concursoswatch.model.DashboardManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DashboardStoreTest {
    @Test
    fun failedPromotionRestoresLastKnownGood() {
        val root = Files.createTempDirectory("dashboard-store").toFile()
        val validator = DashboardValidator("concursos-watch-v4.example.workers.dev", "4.0.0-dev")
        val stable = DashboardStore(root, validator)
        stable.promote(validBundle(1))
        assertEquals(1L, stable.current()!!.dashboardVersion)

        val failingOps = object : DashboardFileOps by RealDashboardFileOps {
            override fun move(source: File, target: File) {
                if (source.name.startsWith("staging-") && target.name == "current") {
                    throw IllegalStateException("simulated promotion failure")
                }
                RealDashboardFileOps.move(source, target)
            }
        }
        val failing = DashboardStore(root, validator, failingOps)
        runCatching { failing.promote(validBundle(2)) }

        val restored = stable.current()
        assertNotNull(restored)
        assertEquals(1L, restored!!.dashboardVersion)
    }

    @Test
    fun currentRejectsTamperedHtml() {
        val root = Files.createTempDirectory("dashboard-store-tamper").toFile()
        val validator = DashboardValidator("concursos-watch-v4.example.workers.dev", "4.0.0-dev")
        val store = DashboardStore(root, validator)
        val stored = store.promote(validBundle(7))
        File(stored.rootDir, "index.html").writeText("<main>tampered</main>")
        assertEquals(null, store.current())
    }

    private fun validBundle(version: Long): DashboardBundle {
        val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"/assets/dashboard.css?v=$version\"></head><body><main>v$version</main></body></html>".toByteArray()
        val css = "body{margin:$version}px".toByteArray()
        return DashboardBundle(
            DashboardManifest(
                schemaVersion = 1,
                dashboardVersion = version,
                styleVersion = version,
                minAppVersion = "4.0.0",
                publishedAt = "2026-08-24T00:00:00Z",
                htmlUrl = "https://concursos-watch-v4.example.workers.dev/dashboard",
                cssUrl = "https://concursos-watch-v4.example.workers.dev/assets/dashboard.css?v=$version",
                htmlSha256 = DashboardValidator.sha256Hex(html),
                cssSha256 = DashboardValidator.sha256Hex(css),
                etag = "\"v$version\"",
            ),
            html,
            css,
        )
    }
}
