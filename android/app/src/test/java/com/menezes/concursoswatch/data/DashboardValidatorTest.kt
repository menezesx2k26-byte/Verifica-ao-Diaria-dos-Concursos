package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardManifest
import com.menezes.concursoswatch.model.DashboardValidation
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardValidatorTest {
    private val validator = DashboardValidator(
        officialHost = "concursos-watch-v4.example.workers.dev",
        currentAppVersion = "4.0.0-dev",
    )

    @Test
    fun rejectsHashMismatch() {
        val html = "<main>ok</main>".toByteArray()
        val css = "body{}".toByteArray()
        val manifest = fixtureManifest(
            htmlSha256 = "00".repeat(32),
            cssSha256 = DashboardValidator.sha256Hex(css),
        )
        assertTrue(validator.validateBundle(manifest, html, css) is DashboardValidation.Invalid)
    }

    @Test
    fun rejectsJavascriptAndRemoteResources() {
        val html = "<script>alert(1)</script><img src=\"https://evil.example/x.png\">"
        assertTrue(validator.validateHtml(html) is DashboardValidation.Invalid)
    }

    @Test
    fun rejectsUnsupportedManifestHost() {
        val manifest = fixtureManifest(htmlUrl = "https://evil.example/dashboard")
        assertTrue(validator.validateManifest(manifest) is DashboardValidation.Invalid)
    }

    @Test
    fun rejectsDashboardThatRequiresNewerApp() {
        val manifest = fixtureManifest(minAppVersion = "4.1.0")
        assertTrue(validator.validateManifest(manifest) is DashboardValidation.Invalid)
    }

    @Test
    fun acceptsExactSafeBundle() {
        val html = "<!doctype html><html><head><link rel=\"stylesheet\" href=\"/assets/dashboard.css?v=1\"></head><body><main>ok</main></body></html>".toByteArray()
        val css = "body{margin:0}".toByteArray()
        val manifest = fixtureManifest(
            htmlSha256 = DashboardValidator.sha256Hex(html),
            cssSha256 = DashboardValidator.sha256Hex(css),
        )
        assertTrue(validator.validateManifest(manifest) is DashboardValidation.Valid)
        assertTrue(validator.validateBundle(manifest, html, css) is DashboardValidation.Valid)
    }

    private fun fixtureManifest(
        minAppVersion: String = "4.0.0",
        htmlUrl: String = "https://concursos-watch-v4.example.workers.dev/dashboard",
        cssUrl: String = "https://concursos-watch-v4.example.workers.dev/assets/dashboard.css?v=1",
        htmlSha256: String = "11".repeat(32),
        cssSha256: String = "22".repeat(32),
    ) = DashboardManifest(
        schemaVersion = 1,
        dashboardVersion = 1,
        styleVersion = 1,
        minAppVersion = minAppVersion,
        publishedAt = "2026-08-24T00:00:00Z",
        htmlUrl = htmlUrl,
        cssUrl = cssUrl,
        htmlSha256 = htmlSha256,
        cssSha256 = cssSha256,
        etag = "\"fixture\"",
    )
}
