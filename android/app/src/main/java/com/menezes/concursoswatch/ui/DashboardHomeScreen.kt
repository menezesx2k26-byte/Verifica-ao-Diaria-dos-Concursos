package com.menezes.concursoswatch.ui

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import com.menezes.concursoswatch.data.DashboardUiState
import com.menezes.concursoswatch.model.Contest

internal enum class DashboardHomeMode { DYNAMIC, FALLBACK }

internal fun dashboardHomeMode(state: DashboardUiState): DashboardHomeMode =
    if (state is DashboardUiState.Ready) DashboardHomeMode.DYNAMIC else DashboardHomeMode.FALLBACK

@Composable
fun DashboardHomeScreen(
    vm: AppViewModel,
    onOpenContests: () -> Unit,
    onOpenAlerts: () -> Unit,
    onDetail: (Contest) -> Unit,
    onNativeRoute: (Uri) -> Unit,
) {
    val dashboard = vm.state.dashboard
    val uriHandler = LocalUriHandler.current

    when (dashboardHomeMode(dashboard)) {
        DashboardHomeMode.DYNAMIC -> {
            val ready = dashboard as DashboardUiState.Ready
            DashboardWebView(
                stored = ready.dashboard,
                onNativeRoute = onNativeRoute,
                onExternalUrl = { uri -> uriHandler.openUri(uri.toString()) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        DashboardHomeMode.FALLBACK -> HomeScreen(
            vm = vm,
            onOpenContests = onOpenContests,
            onOpenAlerts = onOpenAlerts,
            onDetail = onDetail,
        )
    }
}
