package com.menezes.concursoswatch.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.menezes.concursoswatch.data.DashboardStore
import com.menezes.concursoswatch.data.StoredDashboard
import java.io.File

internal enum class DashboardNavigation { Native, External, Blocked }

internal object DashboardWebViewSecurity {
    fun apply(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = false
            domStorageEnabled = false
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setGeolocationEnabled(false)
        }
        webView.isLongClickable = false
    }

    fun classify(uri: Uri, officialHost: String): DashboardNavigation {
        if (uri.scheme.equals("concursoswatch", ignoreCase = true)) {
            return if (uri.host.equals("contest", ignoreCase = true) || uri.host.equals("alerts", ignoreCase = true)) {
                DashboardNavigation.Native
            } else {
                DashboardNavigation.Blocked
            }
        }

        if (uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals(officialHost, ignoreCase = true) &&
            uri.userInfo == null &&
            uri.port == -1
        ) {
            return DashboardNavigation.External
        }
        return DashboardNavigation.Blocked
    }
}

@Composable
fun DashboardWebView(
    stored: StoredDashboard,
    onNativeRoute: (Uri) -> Unit,
    onExternalUrl: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val officialHost = Uri.parse(stored.manifest.htmlUrl).host.orEmpty()

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val assetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/dashboard/") { path ->
                    when (path.substringBefore('?')) {
                        "", DashboardStore.HTML_FILE -> localResponse(
                            File(stored.rootDir, DashboardStore.HTML_FILE),
                            "text/html",
                        )
                        else -> notFound()
                    }
                }
                .addPathHandler("/assets/") { path ->
                    when (path.substringBefore('?')) {
                        DashboardStore.CSS_FILE -> localResponse(
                            File(stored.rootDir, DashboardStore.CSS_FILE),
                            "text/css",
                        )
                        else -> notFound()
                    }
                }
                .build()

            WebView(context).apply {
                DashboardWebViewSecurity.apply(this)
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val uri = request.url
                        if (uri.scheme == "https" && uri.host == LOCAL_ASSET_HOST) {
                            return assetLoader.shouldInterceptRequest(uri) ?: notFound()
                        }
                        return blockedResponse()
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val uri = request.url
                        if (uri.scheme == "https" && uri.host == LOCAL_ASSET_HOST) return false
                        when (DashboardWebViewSecurity.classify(uri, officialHost)) {
                            DashboardNavigation.Native -> onNativeRoute(uri)
                            DashboardNavigation.External -> onExternalUrl(uri)
                            DashboardNavigation.Blocked -> Unit
                        }
                        return true
                    }
                }
                loadUrl(LOCAL_DASHBOARD_URL)
            }
        },
        update = { webView ->
            DashboardWebViewSecurity.apply(webView)
        },
    )
}

private fun localResponse(file: File, mime: String): WebResourceResponse {
    if (!file.isFile) return notFound()
    return WebResourceResponse(mime, "utf-8", file.inputStream())
}

private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    403,
    "Blocked",
    mapOf("Cache-Control" to "no-store"),
    "blocked".byteInputStream(),
)

private fun notFound(): WebResourceResponse = WebResourceResponse(
    "text/plain",
    "utf-8",
    404,
    "Not Found",
    mapOf("Cache-Control" to "no-store"),
    "not found".byteInputStream(),
)

private const val LOCAL_ASSET_HOST = "appassets.androidplatform.net"
private const val LOCAL_DASHBOARD_URL = "https://$LOCAL_ASSET_HOST/dashboard/index.html"
