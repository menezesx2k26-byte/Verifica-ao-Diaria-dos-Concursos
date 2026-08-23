package com.menezes.concursoswatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menezes.concursoswatch.BuildConfig
import com.menezes.concursoswatch.model.SourceHealth

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.state.settings
    val health = vm.state.sourceHealth
    LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            ScreenHeader("Configurações", "Notificações, relevância e integridade")
            ToggleCard("Notificar Federal", s.notifyFederal) { vm.saveSettings(s.copy(notifyFederal = it)) }
            ToggleCard("Notificar Santa Catarina", s.notifySantaCatarina) { vm.saveSettings(s.copy(notifySantaCatarina = it)) }
            ToggleCard("Notificar Região Sul", s.notifySul) { vm.saveSettings(s.copy(notifySul = it)) }
            ToggleCard("Notificar Baixada Santista", s.notifyBaixada) { vm.saveSettings(s.copy(notifyBaixada = it)) }
            ToggleCard("Só inscrições abertas", s.notifyOnlyOpen) { vm.saveSettings(s.copy(notifyOnlyOpen = it)) }
            ToggleCard("Só oportunidades aderentes/prioritárias", s.notifyOnlyRelevant) { vm.saveSettings(s.copy(notifyOnlyRelevant = it)) }
            OutlinedTextField(
                value = s.priorityKeywords,
                onValueChange = { vm.saveSettings(s.copy(priorityKeywords = it)) },
                label = { Text("Palavras prioritárias") },
                supportingText = { Text("Separadas por vírgula; usadas apenas nas notificações.") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                minLines = 3,
            )
            Spacer(Modifier.height(10.dp))
            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Saúde do monitor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${vm.state.healthySources} de ${vm.state.sourceCount} fontes passaram em HTTP + parser + validação semântica.", color = AppMuted)
                    vm.state.contestError?.let { Text("Feed de concursos: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    vm.state.alertError?.let { Text("Feed de alertas: $it", color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    Spacer(Modifier.height(10.dp))
                    Text("Versão: ${BuildConfig.VERSION_NAME}", color = AppMuted)
                    Text("Release detectada: ${vm.state.latestRelease ?: "não verificada"}", color = AppMuted)
                    Button(onClick = vm::syncNow, enabled = !vm.state.syncing, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Verificar agora") }
                }
            }
            ScreenHeader("Fontes", "Falha HTTP e falha semântica são estados diferentes")
        }
        if (health.isEmpty()) item { EmptyCard("Ainda não há telemetria de fontes.") }
        else items(health, key = { it.id }) { SourceHealthCard(it) }
    }
}

@Composable
private fun ToggleCard(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun SourceHealthCard(h: SourceHealth) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
        Column(Modifier.padding(14.dp)) {
            Text(h.label.ifBlank { h.id }, fontWeight = FontWeight.Bold)
            Text(
                "HTTP ${if (h.httpOk) "OK" else "FALHA"} · parser ${if (h.parserOk) "OK" else "FALHA"} · semântico ${if (h.semanticOk) "OK" else "FALHA"}",
                color = if (h.ok) AppGreen else androidx.compose.ui.graphics.Color(0xFFFFB74D), fontSize = 12.sp,
            )
            Text("Itens: ${h.itemCount} · mínimo esperado: ${h.expectedMin} · verificado ${h.checkedAt}", color = AppMuted, fontSize = 11.sp)
            if (h.error.isNotBlank()) Text(h.error, color = androidx.compose.material3.MaterialTheme.colorScheme.error, fontSize = 11.sp)
        }
    }
}
