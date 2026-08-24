package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
    val firstSyncPending = s.lastSync == 0L
    val actionable = s.alerts.filter { it.unread && it.priority >= 70 }
    val open = s.contests.filter { it.active && it.status in setOf("open", "closing_soon") }
    val degraded = s.sourceHealth.filterNot { it.ok }

    LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            ScreenHeader("Concursos Watch", "Se tiver algo importante, aparece aqui primeiro") {
                IconButton(onClick = vm::syncNow, enabled = !s.syncing) {
                    if (s.syncing) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp), strokeWidth = 2.dp, color = AppPurple)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar agora", tint = AppPurple)
                    }
                }
            }

            OverviewCard(
                syncing = s.syncing,
                hasSynced = !firstSyncPending,
                actionableCount = actionable.size,
                openCount = open.size,
                sourceCount = s.sourceCount,
                degradedCount = degraded.size,
                lastSync = vm.relativeSync(),
                onSync = vm::syncNow,
            )
        }

        item {
            SectionHeader(
                title = "Atenção agora",
                subtitle = when {
                    firstSyncPending -> "Confirmando seus acompanhamentos"
                    actionable.isEmpty() -> "Nada importante pendente"
                    else -> "${actionable.size} item(ns) aguardando sua leitura"
                },
                actionLabel = if (actionable.isNotEmpty()) "Ver todos" else null,
                onAction = if (actionable.isNotEmpty()) onOpenAlerts else null,
            )
        }
        if (actionable.isEmpty()) {
            item { if (firstSyncPending) WaitingStateCard() else CalmStateCard() }
        } else {
            items(actionable.take(3), key = { it.id }) { a ->
                AlertCard(a) { vm.markAlertRead(a) }
            }
        }

        item {
            SectionHeader(
                title = "Seus acompanhamentos",
                subtitle = "Os três processos que recebem atenção reforçada",
            )
        }
        item {
            PriorityWatchCard(
                "Praia Grande",
                "ACS · Edital 004/2024",
                s.sourceHealth.filter { it.id.contains("pg_acs") || it.label.contains("ACS 004/2024", true) },
            )
        }
        item {
            PriorityWatchCard(
                "São Vicente",
                "Assistente-Técnico de Gestão · 02/2026",
                s.sourceHealth.filter { it.id.contains("sv_atg") || it.label.contains("ATG", true) || it.label.contains("Assistente-Técnico", true) },
            )
        }
        item {
            PriorityWatchCard(
                "Praia Grande",
                "Professor III · Matemática · 002/2025",
                s.sourceHealth.filter { it.id.contains("pg_math") || it.label.contains("Professor III", true) },
            )
        }

        item {
            SectionHeader(
                title = "Inscrições abertas",
                subtitle = when {
                    firstSyncPending -> "Buscando oportunidades nas fontes oficiais"
                    open.isEmpty() -> "Nenhuma confirmada agora"
                    else -> "${open.size} oportunidade(s) no radar"
                },
                actionLabel = "Ver concursos",
                onAction = onOpenContests,
            )
        }
        if (open.isEmpty()) {
            item {
                EmptyCard(
                    if (firstSyncPending) "A primeira busca está em andamento. Os editais encontrados vão aparecer aqui."
                    else "Quando aparecer um edital com inscrição aberta, ele entra aqui automaticamente.",
                )
            }
        } else {
            items(open.take(4), key = { it.id }) { ContestCard(it, vm::toggleFavorite, onDetail) }
        }

        if (!firstSyncPending && degraded.isNotEmpty()) {
            item {
                SectionHeader("Monitoramento", "Há fontes que precisam de nova confirmação")
                MonitorWarningCard(degraded.size)
            }
        }
    }
}

@Composable
private fun OverviewCard(
    syncing: Boolean,
    hasSynced: Boolean,
    actionableCount: Int,
    openCount: Int,
    sourceCount: Int,
    degradedCount: Int,
    lastSync: String,
    onSync: () -> Unit,
) {
    val hasAction = actionableCount > 0
    val sourceKnown = sourceCount > 0
    val accent = when {
        syncing || !hasSynced -> AppPurple
        hasAction || degradedCount > 0 -> AppAmber
        else -> AppGreen
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = accent.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(
                        when {
                            syncing || !hasSynced -> Icons.Filled.Refresh
                            hasAction || degradedCount > 0 -> Icons.Filled.ErrorOutline
                            else -> Icons.Filled.CheckCircle
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Column(Modifier.weight(1f).padding(start = 13.dp)) {
                    Text(
                        when {
                            syncing -> "Atualizando seus concursos…"
                            !hasSynced -> "Primeira verificação pendente"
                            hasAction -> "Tem coisa nova para você"
                            degradedCount > 0 -> "Tudo calmo, com uma ressalva"
                            else -> "Tudo tranquilo por enquanto"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (!hasSynced && syncing) "Primeira verificação em andamento" else "Última atualização $lastSync",
                        color = AppMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard("Alertas", if (hasSynced) actionableCount.toString() else "—", Modifier.weight(1f), if (actionableCount > 0) AppAmber else AppMuted)
                MetricCard("Abertos", if (hasSynced) openCount.toString() else "—", Modifier.weight(1f), AppPurple)
                MetricCard(
                    "Fontes",
                    when {
                        !sourceKnown -> "—"
                        degradedCount == 0 -> "OK"
                        else -> "$degradedCount!"
                    },
                    Modifier.weight(1f),
                    when {
                        !sourceKnown -> AppMuted
                        degradedCount == 0 -> AppGreen
                        else -> AppAmber
                    },
                )
            }

            FilledTonalButton(
                onClick = onSync,
                enabled = !syncing,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Text(if (syncing) "Atualizando…" else "Verificar agora")
            }
        }
    }
}

@Composable
private fun MetricCard(label: String, value: String, modifier: Modifier, valueColor: Color) {
    Surface(
        modifier = modifier,
        color = AppPanel2,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Text(value, color = valueColor, fontSize = 21.sp, fontWeight = FontWeight.Bold)
            Text(label, color = AppMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun WaitingStateCard() {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppPurple.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = AppPurple)
            Column(Modifier.padding(start = 12.dp)) {
                Text("Conferindo as fontes oficiais", fontWeight = FontWeight.SemiBold)
                Text("O aplicativo ainda não concluiu a primeira verificação.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CalmStateCard() {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF101A16)),
        border = BorderStroke(1.dp, AppGreen.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AppGreen)
            Column(Modifier.padding(start = 12.dp)) {
                Text("Nenhuma ação necessária", fontWeight = FontWeight.SemiBold)
                Text("Você não tem convocação, nomeação ou alerta prioritário sem ler.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PriorityWatchCard(
    city: String,
    title: String,
    sources: List<SourceHealth>,
) {
    val known = sources.isNotEmpty()
    val ok = known && sources.all { it.ok }
    val warning = known && !ok
    val accent = when {
        ok -> AppGreen
        warning -> AppAmber
        else -> AppMuted
    }
    val status = when {
        !known -> "Aguardando primeira verificação"
        ok -> "Monitoramento normal"
        else -> "Precisa de nova confirmação"
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = accent.copy(alpha = 0.12f), shape = RoundedCornerShape(999.dp)) {
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(city.uppercase(), color = AppPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp)
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 2.dp))
                Text(status, color = accent, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun MonitorWarningCard(count: Int) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF221B12)),
        border = BorderStroke(1.dp, AppAmber.copy(alpha = 0.22f)),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("$count fonte(s) não puderam ser confirmadas", fontWeight = FontWeight.SemiBold, color = AppAmber)
            Text(
                "Isso não é tratado como ‘sem novidade’. O aplicativo vai tentar novamente nas próximas verificações.",
                color = AppMuted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}
