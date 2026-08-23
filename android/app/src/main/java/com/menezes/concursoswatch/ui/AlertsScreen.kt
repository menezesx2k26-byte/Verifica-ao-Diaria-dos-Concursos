package com.menezes.concursoswatch.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun AlertsScreen(vm: AppViewModel) {
    val alerts = vm.state.alerts
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Alertas", "Histórico real com estado lido/não lido") {
                TextButton(onClick = vm::markAllAlertsRead) { Text("Marcar lidos") }
            }
        }
        if (alerts.isEmpty()) item { EmptyCard("Nenhum alerta importante armazenado.") }
        else items(alerts, key = { it.id }) { alert -> AlertCard(alert) { vm.markAlertRead(alert) } }
    }
}
