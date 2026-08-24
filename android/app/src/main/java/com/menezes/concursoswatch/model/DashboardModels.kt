package com.menezes.concursoswatch.model

data class DashboardManifest(
    val schemaVersion: Int,
    val dashboardVersion: Long,
    val styleVersion: Long,
    val minAppVersion: String,
    val publishedAt: String,
    val htmlUrl: String,
    val cssUrl: String,
    val htmlSha256: String,
    val cssSha256: String,
    val etag: String,
)

data class DashboardBundle(
    val manifest: DashboardManifest,
    val html: ByteArray,
    val css: ByteArray,
)

sealed interface DashboardValidation {
    data object Valid : DashboardValidation
    data class Invalid(val reason: String) : DashboardValidation
}
