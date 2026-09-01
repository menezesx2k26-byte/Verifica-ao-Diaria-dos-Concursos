package com.menezes.concursoswatch.ui

import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardWebViewSecurityTest {
    @Test
    fun webViewIsLockedBeforeLoadingDashboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var javaScriptEnabled = true
        var domStorageEnabled = true
        var allowFileAccess = true
        var allowContentAccess = true
        var mixedContentMode = -1
        var blockNetworkLoads = false

        instrumentation.runOnMainSync {
            val webView = WebView(instrumentation.targetContext)
            DashboardWebViewSecurity.apply(webView)
            javaScriptEnabled = webView.settings.javaScriptEnabled
            domStorageEnabled = webView.settings.domStorageEnabled
            allowFileAccess = webView.settings.allowFileAccess
            allowContentAccess = webView.settings.allowContentAccess
            mixedContentMode = webView.settings.mixedContentMode
            blockNetworkLoads = webView.settings.blockNetworkLoads
            webView.destroy()
        }

        assertFalse(javaScriptEnabled)
        assertFalse(domStorageEnabled)
        assertFalse(allowFileAccess)
        assertFalse(allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, mixedContentMode)
        assertTrue(blockNetworkLoads)
    }

    @Test
    fun navigationAllowsOnlyKnownNativeRoutesAndOfficialHttps() {
        val host = "concursos-watch.example.workers.dev"

        assertEquals(
            DashboardNavigation.Native,
            DashboardWebViewSecurity.classify(Uri.parse("concursoswatch://contest/abc"), host),
        )
        assertEquals(
            DashboardNavigation.Native,
            DashboardWebViewSecurity.classify(Uri.parse("concursoswatch://alerts/priority"), host),
        )
        assertEquals(
            DashboardNavigation.External,
            DashboardWebViewSecurity.classify(Uri.parse("https://$host/api/v1/contests/abc"), host),
        )
        assertEquals(
            DashboardNavigation.Blocked,
            DashboardWebViewSecurity.classify(Uri.parse("https://evil.example/phish"), host),
        )
        assertEquals(
            DashboardNavigation.Blocked,
            DashboardWebViewSecurity.classify(Uri.parse("javascript:alert(1)"), host),
        )
    }
}
