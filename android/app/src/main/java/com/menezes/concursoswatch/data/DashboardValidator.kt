package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardManifest
import com.menezes.concursoswatch.model.DashboardValidation
import java.net.URI
import java.security.MessageDigest

class DashboardValidator(
    private val officialHost: String,
    private val currentAppVersion: String,
) {
    fun validateManifest(manifest: DashboardManifest): DashboardValidation {
        if (manifest.schemaVersion != 1) return invalid("unsupported_schema")
        if (!versionAtLeast(currentAppVersion, manifest.minAppVersion)) return invalid("app_too_old")
        if (!validAssetUrl(manifest.htmlUrl, "/dashboard")) return invalid("invalid_html_url")
        if (!validAssetUrl(manifest.cssUrl, "/assets/dashboard.css")) return invalid("invalid_css_url")
        if (!HASH.matches(manifest.htmlSha256) || !HASH.matches(manifest.cssSha256)) return invalid("invalid_hash")
        if (manifest.etag.isBlank()) return invalid("missing_etag")
        return DashboardValidation.Valid
    }

    fun validateBundle(
        manifest: DashboardManifest,
        html: ByteArray,
        css: ByteArray,
    ): DashboardValidation {
        val manifestValidation = validateManifest(manifest)
        if (manifestValidation is DashboardValidation.Invalid) return manifestValidation
        if (html.size > MAX_HTML_BYTES) return invalid("html_too_large")
        if (css.size > MAX_CSS_BYTES) return invalid("css_too_large")
        if (sha256Hex(html) != manifest.htmlSha256.lowercase()) return invalid("html_hash_mismatch")
        if (sha256Hex(css) != manifest.cssSha256.lowercase()) return invalid("css_hash_mismatch")

        val htmlValidation = validateHtml(html.toString(Charsets.UTF_8))
        if (htmlValidation is DashboardValidation.Invalid) return htmlValidation
        val cssValidation = validateCss(css.toString(Charsets.UTF_8))
        if (cssValidation is DashboardValidation.Invalid) return cssValidation
        return DashboardValidation.Valid
    }

    fun validateHtml(html: String): DashboardValidation {
        if (FORBIDDEN_HTML_TAG.containsMatchIn(html)) return invalid("forbidden_html_tag")
        if (META_REFRESH.containsMatchIn(html)) return invalid("meta_refresh")
        if (EVENT_HANDLER.containsMatchIn(html)) return invalid("event_handler")
        if (JAVASCRIPT_SCHEME.containsMatchIn(html)) return invalid("javascript_scheme")

        for (match in URL_ATTRIBUTE.findAll(html)) {
            val attribute = match.groupValues[1].lowercase()
            val value = match.groupValues[3].trim()
            if (!allowedHtmlUrl(attribute, value)) return invalid("remote_resource")
        }
        return DashboardValidation.Valid
    }

    fun validateCss(css: String): DashboardValidation {
        val lower = css.lowercase()
        if ("@import" in lower) return invalid("css_import")
        if ("javascript:" in lower || "expression(" in lower) return invalid("css_execution")
        if (Regex("url\\s*\\(\\s*['\"]?\\s*(?:https?:|//)", RegexOption.IGNORE_CASE).containsMatchIn(css)) {
            return invalid("css_remote_url")
        }
        return DashboardValidation.Valid
    }

    private fun allowedHtmlUrl(attribute: String, raw: String): Boolean {
        if (raw.isBlank() || raw.startsWith("#")) return true
        if (raw.startsWith("data:image/", ignoreCase = true) && attribute == "src") return true
        if (raw.startsWith("/assets/dashboard.css") && attribute == "href") return true
        if (raw.startsWith("concursoswatch://", ignoreCase = true) && attribute == "href") return true
        return runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals(officialHost, ignoreCase = true) &&
                attribute == "href"
        }.getOrDefault(false)
    }

    private fun validAssetUrl(raw: String, expectedPath: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(officialHost, ignoreCase = true) &&
            uri.path == expectedPath &&
            uri.userInfo == null &&
            uri.fragment == null
    }.getOrDefault(false)

    private fun invalid(reason: String) = DashboardValidation.Invalid(reason)

    companion object {
        private const val MAX_HTML_BYTES = 512 * 1024
        private const val MAX_CSS_BYTES = 256 * 1024
        private val HASH = Regex("^[a-fA-F0-9]{64}$")
        private val FORBIDDEN_HTML_TAG = Regex(
            "<\\s*(?:script|iframe|object|embed|form|base)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val META_REFRESH = Regex(
            "<\\s*meta\\b[^>]*http-equiv\\s*=\\s*(['\"]?)refresh\\1",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val EVENT_HANDLER = Regex("\\son[a-z0-9_-]+\\s*=", RegexOption.IGNORE_CASE)
        private val JAVASCRIPT_SCHEME = Regex("javascript\\s*:", RegexOption.IGNORE_CASE)
        private val URL_ATTRIBUTE = Regex(
            "\\b(src|href)\\s*=\\s*(['\"])(.*?)\\2",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )

        fun sha256Hex(bytes: ByteArray): String = MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun versionAtLeast(currentRaw: String, requiredRaw: String): Boolean {
            val current = versionParts(currentRaw)
            val required = versionParts(requiredRaw)
            val width = maxOf(current.size, required.size, 3)
            for (index in 0 until width) {
                val a = current.getOrElse(index) { 0 }
                val b = required.getOrElse(index) { 0 }
                if (a != b) return a > b
            }
            return true
        }

        private fun versionParts(raw: String): List<Int> = raw
            .substringBefore('-')
            .split('.')
            .map { segment -> segment.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}
