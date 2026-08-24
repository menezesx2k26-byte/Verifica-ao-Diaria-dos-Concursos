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
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext)
            DashboardWebViewSecurity.apply(webView)
        }

        assertFalse(webView.settings.javaScriptEnabled)
        assertFalse(webView.settings.domStorageEnabled)
        assertFalse(webView.settings.allowFileAccess)
        assertFalse(webView.settings.allowContentAccess)
        assertEquals(WebSettings.MIXED_CONTENT_NEVER_ALLOW, webView.settings.mixedContentMode)
        assertTrue(webView.settings.blockNetworkLoads)

        instrumentation.runOnMainSync { webView.destroy() }
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
