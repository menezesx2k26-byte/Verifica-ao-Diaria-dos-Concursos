package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menezes.concursoswatch.BuildConfig
import com.menezes.concursoswatch.model.SourceHealth

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val s = vm.state.settings
    val health = vm.state.sourceHealth

    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            ScreenHeader("Configurações", "Escolha o que merece virar notificação")

            SettingsSectionTitle("REGIÕES")
            SettingsGroup {
                ToggleRow("Concursos federais", "Em qualquer estado do Brasil", s.notifyFederal) { vm.saveSettings(s.copy(notifyFederal = it)) }
                ToggleRow("Santa Catarina", "Prioridade para oportunidades em SC", s.notifySantaCatarina) { vm.saveSettings(s.copy(notifySantaCatarina = it)) }
                ToggleRow("Região Sul", "Paraná, Santa Catarina e Rio Grande do Sul", s.notifySul) { vm.saveSettings(s.copy(notifySul = it)) }
                ToggleRow("Baixada Santista", "Praia Grande, Santos, São Vicente, Cubatão e Guarujá", s.notifyBaixada) { vm.saveSettings(s.copy(notifyBaixada = it)) }
            }

            SettingsSectionTitle("FILTRO DAS NOTIFICAÇÕES")
            SettingsGroup {
                ToggleRow("Só inscrições abertas", "Evita avisar oportunidade que ainda não aceita inscrição", s.notifyOnlyOpen) { vm.saveSettings(s.copy(notifyOnlyOpen = it)) }
                ToggleRow("Só o que combina comigo", "Usa área, escolaridade, prioridade e palavras de interesse", s.notifyOnlyRelevant) { vm.saveSettings(s.copy(notifyOnlyRelevant = it)) }
            }

            SettingsSectionTitle("PALAVRAS DE INTERESSE")
            OutlinedTextField(
                value = s.priorityKeywords,
                onValueChange = { vm.saveSettings(s.copy(priorityKeywords = it)) },
                label = { Text("Ex.: matemática, estágio, IFSC, TJSC") },
                supportingText = { Text("Separe por vírgula. Isso afeta apenas as notificações, não esconde concursos da lista.") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                minLines = 3,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AppPurple,
                    unfocusedBorderColor = AppDivider,
                    focusedContainerColor = AppPanel,
                    unfocusedContainerColor = AppPanel,
                ),
            )

            SettingsSectionTitle("ESTADO DO APLICATIVO")
            MonitorSummaryCard(vm)

            SettingsSectionTitle("DIAGNÓSTICO DAS FONTES")
            Text(
                "Esta área é técnica. Ela serve para conferir se uma fonte respondeu e se o conteúdo esperado pôde ser reconhecido.",
                color = AppMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            Spacer(Modifier.height(6.dp))
        }

        if (health.isEmpty()) {
            item { EmptyCard("Ainda não há diagnóstico das fontes. Faça uma verificação para preencher esta área.") }
        } else {
            items(health, key = { it.id }) { SourceHealthCard(it) }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(
        text,
        color = AppMuted2,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.9.sp,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun ToggleRow(label: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            Text(description, color = AppMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun MonitorSummaryCard(vm: AppViewModel) {
    val total = vm.state.sourceCount
    val healthy = vm.state.healthySources
    val allOk = total > 0 && healthy == total

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                if (allOk) "Monitoramento funcionando normalmente" else "Monitoramento com ressalvas",
                color = if (allOk) AppGreen else AppAmber,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (total == 0) "Ainda não há leitura das fontes."
                else "$healthy de $total fontes estão confirmadas na leitura mais recente.",
                color = AppMuted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )

            vm.state.contestError?.let {
                Text("Concursos: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            vm.state.alertError?.let {
                Text("Alertas: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(Modifier.height(12.dp))
            Text("Versão instalada: ${BuildConfig.VERSION_NAME}", color = AppMuted2, style = MaterialTheme.typography.bodySmall)
            Text("Versão encontrada: ${vm.state.latestRelease ?: "não verificada"}", color = AppMuted2, style = MaterialTheme.typography.bodySmall)

            Button(
                onClick = vm::syncNow,
                enabled = !vm.state.syncing,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(if (vm.state.syncing) "Verificando…" else "Verificar agora")
            }
        }
    }
}

@Composable
private fun SourceHealthCard(h: SourceHealth) {
    val accent = if (h.ok) AppGreen else AppAmber
    val status = when {
        !h.httpOk -> "Fonte indisponível"
        !h.parserOk -> "Não foi possível ler a página"
        !h.semanticOk -> "Conteúdo esperado não confirmado"
        else -> "Leitura normal"
    }

    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(h.label.ifBlank { h.id }, fontWeight = FontWeight.SemiBold)
            Text(status, color = accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
            Text(
                "Itens encontrados: ${h.itemCount}  ·  última leitura: ${h.checkedAt.ifBlank { "—" }}",
                color = AppMuted2,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (h.error.isNotBlank()) {
                Text(h.error, color = Color(0xFFFF8A8A), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 5.dp))
            }
        }
    }
}
