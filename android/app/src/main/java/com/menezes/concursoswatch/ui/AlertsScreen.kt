package com.menezes.concursoswatch.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

@Composable
fun AlertsScreen(vm: AppViewModel) {
    val alerts = vm.state.alerts
    val unread = alerts.count { it.unread }
    val urgent = alerts.count { it.unread && it.priority >= 100 }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(
                title = "Alertas",
                subtitle = when {
                    unread == 0 -> "Tudo lido por aqui"
                    urgent > 0 -> "$urgent alerta(s) prioritário(s) ainda não lido(s)"
                    else -> "$unread atualização(ões) ainda não lida(s)"
                },
                action = {
                    if (unread > 0) {
                        TextButton(onClick = vm::markAllAlertsRead) {
                            Text("Marcar lidos", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                },
            )
        }

        if (alerts.isEmpty()) {
            item { EmptyCard("Nenhum alerta importante foi registrado ainda.") }
        } else {
            items(alerts, key = { it.id }) { alert ->
                AlertCard(alert) { vm.markAlertRead(alert) }
            }
        }
    }
}
