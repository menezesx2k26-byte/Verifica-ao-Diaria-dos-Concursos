package com.menezes.concursoswatch.ui

import com.menezes.concursoswatch.data.DashboardUiState
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateDashboardTest {
    @Test
    fun dashboardStartsLoading() {
        val state = AppUiState()
        assertTrue(state.dashboard is DashboardUiState.Loading)
    }
}
