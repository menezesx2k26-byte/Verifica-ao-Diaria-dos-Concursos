package com.menezes.concursoswatch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.SourceHealth

@Composable
fun HomeScreen(
    vm: AppViewModel,
    onOpenContests: () -> Unit,
    onOpenAlerts: () -> Unit,
    onDetail: (Contest) -> Unit,
) {
    val s = vm.state
    val actionable = s.alerts.filter { it.unread && it.priority >= 70 }
    val open = s.contests.filter { it.active && it.status in setOf("open", "closing_soon") }
    val healthy = s.sourceHealth.count { it.ok }
    val degraded = s.sourceHealth.filterNot { it.ok }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader("Concursos Watch", "Ação primeiro; telemetria depois") {
                IconButton(onClick = vm::syncNow, enabled = !s.syncing) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Sincronizar agora", tint = AppPurple)
                }
            }
            SystemStateCard(
                syncing = s.syncing,
                hasAction = actionable.isNotEmpty(),
                healthy = healthy,
                total = s.sourceCount,
                lastSync = vm.relativeSync(),
                onSync = vm::syncNow,
            )
            SectionTitle("Atenção agora", if (actionable.isEmpty()) "Nenhuma ação importante pendente" else "${actionable.size} alerta(s) ainda não lido(s)")
        }
        if (actionable.isEmpty()) item { EmptyCard("Nada exige ação imediata neste momento.") }
        else items(actionable.take(4), key = { it.id }) { a -> AlertCard(a) { vm.markAlertRead(a) } }

        item { SectionTitle("Acompanhamentos prioritários", "Saúde semântica das fontes, não apenas HTTP 200") }
        item { PriorityWatchCard("Praia Grande · ACS 004/2024", s.sourceHealth.filter { it.id.contains("pg_acs") || it.label.contains("ACS 004/2024", true) }) }
        item { PriorityWatchCard("São Vicente · ATG 02/2026", s.sourceHealth.filter { it.id.contains("sv_atg") || it.label.contains("02/2026", true) }) }
        item { PriorityWatchCard("Praia Grande · Professor III Matemática", s.sourceHealth.filter { it.id.contains("pg_math") || it.label.contains("Professor III", true) }) }

        item { SectionTitle("Inscrições abertas", "Somente itens ativos do feed canônico") }
        if (open.isEmpty()) item { EmptyCard("Nenhuma inscrição aberta confirmada no feed atual.") }
        else items(open.take(5), key = { it.id }) { ContestCard(it, vm::toggleFavorite, onDetail) }

        item {
            if (degraded.isNotEmpty()) {
                Card(Modifier.fillMaxWidth().padding(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2015))) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Monitor parcialmente degradado", fontWeight = FontWeight.Bold)
                        Text("${degraded.size} fonte(s) não passaram em HTTP + parser + validação semântica. O app não interpreta isso como ‘sem novidade’. ", color = AppMuted, fontSize = 13.sp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onOpenAlerts) { Text("Ver alertas") }
                TextButton(onClick = onOpenContests) { Text("Ver concursos") }
            }
        }
    }
}

@Composable
private fun SystemStateCard(syncing: Boolean, hasAction: Boolean, healthy: Int, total: Int, lastSync: String, onSync: () -> Unit) {
    val color = when {
        syncing -> AppPurple
        hasAction -> Color(0xFFFFB74D)
        total > 0 && healthy < total -> Color(0xFFFFB74D)
        else -> AppGreen
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = AppPanel), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (hasAction) Icons.Filled.Error else Icons.Filled.CheckCircle, contentDescription = null, tint = color)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(if (syncing) "Sincronizando…" else if (hasAction) "Há algo para revisar" else "Sem ação imediata", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Última sincronização $lastSync · fontes válidas $healthy/$total", color = AppMuted, fontSize = 12.sp)
            }
            Button(onClick = onSync, enabled = !syncing) { Text("Agora") }
        }
    }
}

@Composable
private fun PriorityWatchCard(title: String, sources: List<SourceHealth>) {
    val known = sources.isNotEmpty()
    val ok = known && sources.all { it.ok }
    val status = when {
        !known -> "Ainda sem telemetria"
        ok -> "Verificação válida"
        else -> "Verificação inconclusiva"
    }
    val details = when {
        !known -> "O scanner dedicado ainda não publicou estado para este acompanhamento."
        ok -> sources.maxByOrNull { it.checkedAt }?.let { "${it.scanStatusLabel()} · ${it.checkedAt}" } ?: status
        else -> sources.filterNot { it.ok }.joinToString(" · ") { it.error.ifBlank { "fonte/parsing não confirmado" } }.take(260)
    }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
        Column(Modifier.padding(15.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(status, color = if (ok) AppGreen else Color(0xFFFFB74D), fontSize = 13.sp)
            Text(details, color = AppMuted, fontSize = 12.sp)
        }
    }
}

private fun SourceHealth.scanStatusLabel(): String = when {
    !httpOk -> "fonte indisponível"
    !parserOk -> "parser falhou"
    !semanticOk -> "conteúdo esperado não confirmado"
    else -> "sem mudança acionável confirmada"
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = AppMuted, fontSize = 13.sp)
    }
}
