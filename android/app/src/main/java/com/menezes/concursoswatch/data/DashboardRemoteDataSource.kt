package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.BuildConfig
import com.menezes.concursoswatch.model.DashboardBundle
import com.menezes.concursoswatch.model.DashboardManifest
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

enum class AssetKind { MANIFEST, HTML, CSS }

sealed interface DashboardManifestResult {
    data object NotModified : DashboardManifestResult
    data class Modified(val manifest: DashboardManifest) : DashboardManifestResult
}

class DashboardRemoteDataSource(
    private val apiBaseOverride: String? = null,
) {
    @Volatile private var discoveredApiBase: String? = null

    suspend fun fetchManifest(etag: String?): DashboardManifestResult {
        val base = resolveApiBase()
        val result = request(
            url = "$base/api/v1/dashboard-manifest",
            headers = manifestRequestHeaders(etag),
            maxBytes = MAX_MANIFEST_BYTES,
        )
        if (result.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            return DashboardManifestResult.NotModified
        }
        require(result.code in 200..299) { "dashboard manifest HTTP ${result.code}" }
        require(isAllowedMime(result.contentType, AssetKind.MANIFEST)) { "invalid dashboard manifest MIME" }
        val manifest = parseManifest(result.body.toString(Charsets.UTF_8))
        validateManifestUrls(manifest)
        return DashboardManifestResult.Modified(manifest)
    }

    suspend fun fetchBundle(manifest: DashboardManifest): DashboardBundle {
        validateManifestUrls(manifest)
        val html = request(
            url = manifest.htmlUrl,
            headers = mapOf(
                "Accept" to "text/html",
                "User-Agent" to USER_AGENT,
            ),
            maxBytes = MAX_HTML_BYTES,
        )
        require(html.code in 200..299) { "dashboard HTML HTTP ${html.code}" }
        require(isAllowedMime(html.contentType, AssetKind.HTML)) { "invalid dashboard HTML MIME" }

        val css = request(
            url = manifest.cssUrl,
            headers = mapOf(
                "Accept" to "text/css",
                "User-Agent" to USER_AGENT,
            ),
            maxBytes = MAX_CSS_BYTES,
        )
        require(css.code in 200..299) { "dashboard CSS HTTP ${css.code}" }
        require(isAllowedMime(css.contentType, AssetKind.CSS)) { "invalid dashboard CSS MIME" }
        return DashboardBundle(manifest, html.body, css.body)
    }

    fun validateManifestUrls(manifest: DashboardManifest) {
        val base = URI(resolveApiBase())
        require(validAssetUrl(manifest.htmlUrl, base, "/dashboard")) { "dashboard HTML host/path rejected" }
        require(validAssetUrl(manifest.cssUrl, base, "/assets/dashboard.css")) { "dashboard CSS host/path rejected" }
    }

    fun isAllowedMime(raw: String?, kind: AssetKind): Boolean {
        val mime = raw.orEmpty().substringBefore(';').trim().lowercase()
        return when (kind) {
            AssetKind.MANIFEST -> mime == "application/json"
            AssetKind.HTML -> mime == "text/html"
            AssetKind.CSS -> mime == "text/css"
        }
    }

    fun manifestRequestHeaders(etag: String?): Map<String, String> = buildMap {
        put("Accept", "application/json")
        put("User-Agent", USER_AGENT)
        etag?.trim()?.takeIf { it.isNotEmpty() }?.let { put("If-None-Match", it) }
    }

    fun officialHost(): String = URI(resolveApiBase()).host
        ?: throw IllegalStateException("dashboard API host ausente")

    private fun parseManifest(raw: String): DashboardManifest {
        val root = JSONObject(raw)
        return DashboardManifest(
            schemaVersion = root.getInt("schema_version"),
            dashboardVersion = root.getLong("dashboard_version"),
            styleVersion = root.getLong("style_version"),
            minAppVersion = root.getString("min_app_version"),
            publishedAt = root.getString("published_at"),
            htmlUrl = root.getString("html_url"),
            cssUrl = root.getString("css_url"),
            htmlSha256 = root.getString("html_sha256"),
            cssSha256 = root.getString("css_sha256"),
            etag = root.getString("etag"),
        )
    }

    private fun resolveApiBase(): String {
        apiBaseOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }?.let { return validateBase(it) }
        val built = BuildConfig.API_BASE_URL.trim().trimEnd('/')
        if (built.isNotEmpty()) return validateBase(built)
        discoveredApiBase?.let { return it }

        val discovered = runCatching {
            val result = request(
                url = RUNTIME_URL,
                headers = mapOf(
                    "Accept" to "application/json",
                    "User-Agent" to USER_AGENT,
                ),
                maxBytes = MAX_RUNTIME_BYTES,
            )
            require(result.code in 200..299)
            JSONObject(result.body.toString(Charsets.UTF_8))
                .optString("cloudflare_url")
                .trim()
                .trimEnd('/')
        }.getOrDefault("")
        require(discovered.isNotEmpty()) { "dashboard API base indisponível" }
        return validateBase(discovered).also { discoveredApiBase = it }
    }

    private fun validateBase(raw: String): String {
        val uri = URI(raw)
        require(uri.scheme.equals("https", ignoreCase = true)) { "dashboard API must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "dashboard API host ausente" }
        require(uri.userInfo == null && uri.fragment == null && uri.query == null) { "dashboard API base inválida" }
        require(uri.path.isNullOrBlank() || uri.path == "/") { "dashboard API base deve ser origin-only" }
        return "https://${uri.host}${if (uri.port == -1) "" else ":${uri.port}"}"
    }

    private fun validAssetUrl(raw: String, base: URI, expectedPath: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(base.host, ignoreCase = true) &&
            normalizedPort(uri) == normalizedPort(base) &&
            uri.path == expectedPath &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun normalizedPort(uri: URI): Int = when {
        uri.port != -1 -> uri.port
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }

    private fun request(
        url: String,
        headers: Map<String, String>,
        maxBytes: Int,
    ): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            headers.forEach(connection::setRequestProperty)
            val code = connection.responseCode
            require(code !in 300..399 || code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                "dashboard redirects are not allowed"
            }
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return HttpResult(code, connection.contentType, ByteArray(0))
            }
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maxBytes) { "dashboard response too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: ByteArray(0)
            return HttpResult(code, connection.contentType, body)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResult(
        val code: Int,
        val contentType: String?,
        val body: ByteArray,
    )

    companion object {
        private const val USER_AGENT = "ConcursosWatch-Android/4.0"
        private const val RUNTIME_URL = "https://raw.githubusercontent.com/menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos/main/config/runtime.json"
        private const val MAX_RUNTIME_BYTES = 64 * 1024
        private const val MAX_MANIFEST_BYTES = 64 * 1024
        private const val MAX_HTML_BYTES = 512 * 1024
        private const val MAX_CSS_BYTES = 256 * 1024
    }
}
