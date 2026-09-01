package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.DashboardBundle
import com.menezes.concursoswatch.model.DashboardManifest
import com.menezes.concursoswatch.model.DashboardValidation
import org.json.JSONObject
import java.io.File

interface DashboardFileOps {
    fun exists(file: File): Boolean
    fun mkdirs(file: File)
    fun read(file: File): ByteArray
    fun write(file: File, bytes: ByteArray)
    fun move(source: File, target: File)
    fun deleteRecursively(file: File)
}

object RealDashboardFileOps : DashboardFileOps {
    override fun exists(file: File): Boolean = file.exists()

    override fun mkdirs(file: File) {
        require(file.exists() || file.mkdirs()) { "cannot create ${file.path}" }
    }

    override fun read(file: File): ByteArray = file.readBytes()

    override fun write(file: File, bytes: ByteArray) {
        file.parentFile?.let(::mkdirs)
        file.writeBytes(bytes)
    }

    override fun move(source: File, target: File) {
        target.parentFile?.let(::mkdirs)
        if (target.exists()) deleteRecursively(target)
        require(source.renameTo(target)) { "cannot move ${source.path} to ${target.path}" }
    }

    override fun deleteRecursively(file: File) {
        if (file.exists()) require(file.deleteRecursively()) { "cannot delete ${file.path}" }
    }
}

data class StoredDashboard(
    val dashboardVersion: Long,
    val styleVersion: Long,
    val etag: String,
    val rootDir: File,
    val manifest: DashboardManifest,
)

class DashboardStore(
    private val rootDir: File,
    private val validator: DashboardValidator,
    private val fileOps: DashboardFileOps = RealDashboardFileOps,
) {
    private val currentDir get() = File(rootDir, "current")
    private val backupDir get() = File(rootDir, "backup")

    fun current(): StoredDashboard? = runCatching {
        if (!fileOps.exists(currentDir)) return null
        readAndValidate(currentDir)
    }.getOrNull()

    fun promote(bundle: DashboardBundle): StoredDashboard {
        require(validator.validateBundle(bundle.manifest, bundle.html, bundle.css) is DashboardValidation.Valid) {
            "dashboard bundle rejected"
        }
        fileOps.mkdirs(rootDir)
        val staging = File(rootDir, "staging-${bundle.manifest.dashboardVersion}-${System.nanoTime()}")
        fileOps.deleteRecursively(staging)
        fileOps.mkdirs(staging)

        try {
            fileOps.write(File(staging, MANIFEST_FILE), encodeManifest(bundle.manifest))
            fileOps.write(File(staging, HTML_FILE), bundle.html)
            fileOps.write(File(staging, CSS_FILE), bundle.css)
            readAndValidate(staging)

            fileOps.deleteRecursively(backupDir)
            val hadCurrent = fileOps.exists(currentDir)
            if (hadCurrent) fileOps.move(currentDir, backupDir)

            try {
                fileOps.move(staging, currentDir)
                val promoted = readAndValidate(currentDir)
                fileOps.deleteRecursively(backupDir)
                return promoted
            } catch (error: Throwable) {
                if (fileOps.exists(currentDir)) fileOps.deleteRecursively(currentDir)
                if (fileOps.exists(backupDir)) fileOps.move(backupDir, currentDir)
                throw error
            }
        } finally {
            if (fileOps.exists(staging)) runCatching { fileOps.deleteRecursively(staging) }
        }
    }

    private fun readAndValidate(directory: File): StoredDashboard {
        val manifest = decodeManifest(fileOps.read(File(directory, MANIFEST_FILE)))
        val html = fileOps.read(File(directory, HTML_FILE))
        val css = fileOps.read(File(directory, CSS_FILE))
        require(validator.validateBundle(manifest, html, css) is DashboardValidation.Valid) {
            "stored dashboard failed validation"
        }
        return StoredDashboard(
            dashboardVersion = manifest.dashboardVersion,
            styleVersion = manifest.styleVersion,
            etag = manifest.etag,
            rootDir = directory,
            manifest = manifest,
        )
    }

    private fun encodeManifest(manifest: DashboardManifest): ByteArray = JSONObject()
        .put("schema_version", manifest.schemaVersion)
        .put("dashboard_version", manifest.dashboardVersion)
        .put("style_version", manifest.styleVersion)
        .put("min_app_version", manifest.minAppVersion)
        .put("published_at", manifest.publishedAt)
        .put("html_url", manifest.htmlUrl)
        .put("css_url", manifest.cssUrl)
        .put("html_sha256", manifest.htmlSha256)
        .put("css_sha256", manifest.cssSha256)
        .put("etag", manifest.etag)
        .toString()
        .toByteArray(Charsets.UTF_8)

    private fun decodeManifest(bytes: ByteArray): DashboardManifest {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
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

    companion object {
        const val MANIFEST_FILE = "manifest.json"
        const val HTML_FILE = "index.html"
        const val CSS_FILE = "dashboard.css"
    }
}
