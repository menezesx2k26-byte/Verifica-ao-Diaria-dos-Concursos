package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.menezes.concursoswatch.BuildConfig
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.RegionFilter
import com.menezes.concursoswatch.model.StatusFilter

private data class NavItem(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private const val RELEASES_URL = "https://github.com/menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos/releases"

@Composable
fun ConcursosApp(vm: AppViewModel = viewModel()) {
    ConcursosWatchTheme {
        val nav = rememberNavController()
        val current by nav.currentBackStackEntryAsState()
        val route = current?.destination?.route
        val tabs = listOf(
            NavItem("home", "Início", Icons.Filled.Home),
            NavItem("alerts", "Alertas", Icons.Filled.Notifications),
            NavItem("contests", "Concursos", Icons.Filled.Work),
            NavItem("favorites", "Favoritos", Icons.Filled.Favorite),
            NavItem("settings", "Config.", Icons.Filled.Settings),
        )
        Scaffold(
            containerColor = AppBg,
            bottomBar = {
                if (route != "detail") NavigationBar(containerColor = Color(0xFF0A0D12)) {
                    tabs.forEach { item ->
                        NavigationBarItem(
                            selected = route == item.route,
                            onClick = {
                                if (route != item.route) nav.navigate(item.route) {
                                    launchSingleTop = true
                                    popUpTo("home") { saveState = true }
                                    restoreState = true
                                }
                            },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, maxLines = 1, fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = AppPurple, selectedTextColor = AppPurple,
                                indicatorColor = Color(0xFF241638), unselectedIconColor = AppMuted, unselectedTextColor = AppMuted
                            )
                        )
                    }
                }
            }
        ) { padding ->
            NavHost(navController = nav, startDestination = "home", modifier = Modifier.padding(padding)) {
                composable("home") { HomeScreen(vm, { nav.navigate("contests") }, { nav.navigate("alerts") }) { vm.selectContest(it); nav.navigate("detail") } }
                composable("alerts") { AlertsScreen(vm) }
                composable("contests") { ContestsScreen(vm, false) { vm.selectContest(it); nav.navigate("detail") } }
                composable("favorites") { ContestsScreen(vm, true) { vm.selectContest(it); nav.navigate("detail") } }
                composable("settings") { SettingsScreen(vm) }
                composable("detail") { DetailScreen(vm) { nav.popBackStack() } }
            }
        }
    }
}

@Composable
private fun ScreenHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 29.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = AppPurple, fontSize = 15.sp)
        }
        action?.invoke()
    }
}

@Composable
private fun HomeScreen(vm: AppViewModel, onOpenContests: () -> Unit, onOpenAlerts: () -> Unit, onDetail: (Contest) -> Unit) {
    val s = vm.state
    val unreadAlerts = s.alerts.count { it.unread }
    val priorityUnread = s.alerts.count { it.unread && it.priority >= 100 }
    val unreadContests = s.contests.count { it.unread }
    val openContests = s.contests.filter { it.status in setOf("open", "closing_soon") }
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Concursos Watch", "Só o que merece sua atenção") {
                IconButton(onClick = vm::syncNow, enabled = !s.syncing, modifier = Modifier.semantics { contentDescription = "Sincronizar agora" }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, tint = AppPurple)
                }
            }
            SyncStrip(vm)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("$unreadAlerts", "Não lidos", Icons.Filled.Notifications, Modifier.weight(1f), onOpenAlerts)
                StatCard("$priorityUnread", "Prioritários", Icons.Filled.PriorityHigh, Modifier.weight(1f), onOpenAlerts)
                StatCard("$unreadContests", "Novos", Icons.Filled.NewReleases, Modifier.weight(1f), onOpenContests)
                StatCard("${s.healthySources}/${s.sourceCount}", "Fontes", Icons.Filled.AccountBalance, Modifier.weight(1f), null)
            }
            Spacer(Modifier.height(16.dp))
            if (s.contestError != null || s.alertError != null) PartialSyncWarning(s.contestError, s.alertError)
            SectionTitle("Acompanhamentos prioritários", "Só mudanças acionáveis; ruído técnico fica no GitHub")
        }
        if (s.alerts.isEmpty()) item { EmptyCard("Nenhum alerta prioritário armazenado.") }
        else items(s.alerts.take(3), key = { it.id }) { AlertCard(it) { vm.markAlertRead(it) } }
        item { SectionTitle("Inscrições abertas", "Oportunidades estruturadas detectadas nas fontes oficiais") }
        if (openContests.isEmpty()) item { EmptyCard("Nenhuma inscrição aberta foi estruturada no feed ainda.") }
        else items(openContests.take(5), key = { it.id }) { ContestCard(it, vm::toggleFavorite, onDetail) }
        item { TextButton(onClick = onOpenContests, modifier = Modifier.padding(horizontal = 12.dp)) { Text("Ver todos os concursos") } }
    }
}

@Composable
private fun SyncStrip(vm: AppViewModel) {
    val s = vm.state
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = AppPanel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (s.syncing) Icons.Filled.Sync else Icons.Filled.CloudDone, null, tint = if (s.contestError == null && s.alertError == null) AppGreen else AppPurple)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (s.syncing) "Sincronizando…" else "Última sincronização: ${vm.relativeSync()}", fontWeight = FontWeight.SemiBold)
                Text("Ciclo em segundo plano ~15 min; o Android pode atrasar por bateria/Doze.", color = AppMuted, fontSize = 12.sp)
            }
            Button(onClick = vm::syncNow, enabled = !s.syncing) { Text(if (s.syncing) "…" else "Agora") }
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: (() -> Unit)?) {
    Card(modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), colors = CardDefaults.cardColors(containerColor = Color(0xFF221735)), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = AppPurple, modifier = Modifier.size(20.dp))
            Text(value, color = AppPurple, fontWeight = FontWeight.Bold, fontSize = 21.sp, maxLines = 1)
            Text(label, color = Color(0xFFD2B6EF), fontSize = 11.sp, maxLines = 1)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = AppMuted, fontSize = 13.sp)
    }
}

@Composable
private fun PartialSyncWarning(contestError: String?, alertError: String?) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2A2015))) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Color(0xFFFFB74D)); Spacer(Modifier.width(8.dp)); Text("Sincronização parcial", fontWeight = FontWeight.Bold)
            }
            contestError?.let { Text("Concursos: $it", color = AppMuted, fontSize = 12.sp) }
            alertError?.let { Text("Alertas: $it", color = AppMuted, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun AlertsScreen(vm: AppViewModel) {
    val alerts = vm.state.alerts
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader("Alertas", "Histórico real, com lidos e não lidos") { TextButton(onClick = vm::markAllAlertsRead) { Text("Marcar lidos") } }
        }
        if (alerts.isEmpty()) item { EmptyCard("Nenhum alerta importante armazenado.") }
        else items(alerts, key = { it.id }) { AlertCard(it) { vm.markAlertRead(it) } }
    }
}

@Composable
private fun AlertCard(a: AlertItem, markRead: () -> Unit) {
    val uri = LocalUriHandler.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { markRead(); if (a.url.isNotBlank()) uri.openUri(a.url) },
        colors = CardDefaults.cardColors(containerColor = if (a.unread) Color(0xFF20162E) else AppPanel),
        border = if (a.priority >= 100) BorderStroke(1.dp, AppPurple) else null
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, null, tint = if (a.priority >= 100) AppPurple else AppMuted)
                Spacer(Modifier.width(8.dp))
                Text(a.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.unread) Badge { Text("novo") }
            }
            if (a.body.isNotBlank()) Text(a.body.replace(Regex("\\s+"), " ").take(260), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ContestsScreen(vm: AppViewModel, favoritesOnly: Boolean, onDetail: (Contest) -> Unit) {
    val list = vm.filteredContests(favoritesOnly)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(if (favoritesOnly) "Favoritos" else "Concursos", if (favoritesOnly) "O que você salvou" else "Inscrições, editais e processos seletivos")
            if (!favoritesOnly) {
                OutlinedTextField(
                    value = vm.state.search, onValueChange = vm::setSearch,
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, placeholder = { Text("Buscar cargo, órgão, cidade, área…") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true
                )
                RegionFilters(vm)
                StatusFilters(vm)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = vm::markAllContestsRead) { Text("Marcar todos como lidos") }
                }
            }
        }
        if (list.isEmpty()) item { EmptyCard(if (favoritesOnly) "Você ainda não favoritou nenhum concurso." else "Nenhum item combina com os filtros atuais.") }
        else items(list, key = { it.id }) { ContestCard(it, vm::toggleFavorite, onDetail) }
    }
}

@Composable
private fun RegionFilters(vm: AppViewModel) {
    val options = listOf(RegionFilter.ALL to "Todos", RegionFilter.FEDERAL to "Federal", RegionFilter.SC to "Santa Catarina", RegionFilter.SUL to "Sul", RegionFilter.BAIXADA to "SP · Baixada")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = vm.state.regionFilter == value, onClick = { vm.setRegion(value) }, label = { Text(label) }) }
    }
}

@Composable
private fun StatusFilters(vm: AppViewModel) {
    val options = listOf(StatusFilter.ALL to "Todos", StatusFilter.OPEN to "Inscrições abertas", StatusFilter.CLOSING_SOON to "Encerra em 7 dias", StatusFilter.NEW to "Novos")
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) -> FilterChip(selected = vm.state.statusFilter == value, onClick = { vm.setStatus(value) }, label = { Text(label) }) }
    }
}

@Composable
private fun ContestCard(c: Contest, toggleFavorite: (Contest) -> Unit, onDetail: (Contest) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onDetail(c) },
        colors = CardDefaults.cardColors(containerColor = if (c.unread) Color(0xFF1D152B) else AppPanel),
        border = if (c.unread) BorderStroke(1.dp, Color(0xFF5E3484)) else null, shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(c.status)
                        if (c.scope.isNotBlank()) { Spacer(Modifier.width(6.dp)); AssistChip(onClick = {}, label = { Text(c.scope.uppercase(), fontSize = 10.sp) }) }
                    }
                    Text(c.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(c.organization.ifBlank { c.source }, color = AppPurple, fontSize = 13.sp)
                }
                IconButton(onClick = { toggleFavorite(c) }, modifier = Modifier.semantics { contentDescription = if (c.favorite) "Remover dos favoritos" else "Adicionar aos favoritos" }) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, null, tint = if (c.favorite) Color(0xFFFFC857) else AppMuted)
                }
            }
            val meta = listOfNotNull(
                c.city.takeIf { it.isNotBlank() }, c.education.takeIf { it.isNotBlank() }, c.area.takeIf { it.isNotBlank() },
                c.remuneration.takeIf { it.isNotBlank() }, c.vacancies.takeIf { it.isNotBlank() }?.let { "$it vagas/CR" },
                c.endDate.takeIf { it.isNotBlank() }?.let { "até $it" }
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" • "), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, color) = when (status) {
        "open" -> "ABERTO" to AppGreen
        "closing_soon" -> "ENCERRA LOGO" to Color(0xFFFFB74D)
        "closed" -> "ENCERRADO" to AppMuted
        "announced" -> "ANUNCIADO" to AppPurple
        else -> "DETECTADO" to AppPurple
    }
    Surface(color = color.copy(alpha = .15f), shape = RoundedCornerShape(50)) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun DetailScreen(vm: AppViewModel, onBack: () -> Unit) {
    val c = vm.state.selectedContest ?: return
    val uri = LocalUriHandler.current
    LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Voltar") }
                Text("Detalhes", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                StatusPill(c.status)
                Text(c.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(c.organization.ifBlank { c.source }, color = AppPurple, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                DetailRow("Local", listOf(c.city, c.uf).filter { it.isNotBlank() }.joinToString(" · "))
                DetailRow("Esfera", c.scope); DetailRow("Tipo", c.type); DetailRow("Escolaridade", c.education)
                DetailRow("Área", c.area); DetailRow("Remuneração", c.remuneration); DetailRow("Vagas / CR", c.vacancies)
                DetailRow("Taxa", c.fee); DetailRow("Inscrições", listOf(c.startDate, c.endDate).filter { it.isNotBlank() }.joinToString(" → "))
                DetailRow("Fonte", c.source)
                Spacer(Modifier.height(18.dp))
                Button(onClick = { uri.openUri(c.editalUrl.ifBlank { c.url }) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.OpenInNew, null); Spacer(Modifier.width(8.dp)); Text("Abrir fonte oficial")
                }
                OutlinedButton(onClick = { vm.toggleFavorite(c) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, null); Spacer(Modifier.width(8.dp)); Text(if (c.favorite) "Remover dos favoritos" else "Salvar nos favoritos")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) { Text(label, color = AppMuted, fontSize = 12.sp); Text(value, fontSize = 16.sp) }
    HorizontalDivider(color = Color(0xFF252A32))
}

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    val s = vm.state.settings
    val uri = LocalUriHandler.current
    var keywords by remember(s.priorityKeywords) { mutableStateOf(s.priorityKeywords) }
    val installed = BuildConfig.VERSION_NAME.removeSuffix("-dev")
    val latest = vm.state.latestRelease
    val updateAvailable = !latest.isNullOrBlank() && latest != installed
    LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            ScreenHeader("Configurações", "Notificações, prioridades, fontes e integridade")
            SettingsCard("Notificar Federal", s.notifyFederal) { vm.saveSettings(s.copy(notifyFederal = it)) }
            SettingsCard("Notificar Santa Catarina", s.notifySantaCatarina) { vm.saveSettings(s.copy(notifySantaCatarina = it)) }
            SettingsCard("Notificar Região Sul", s.notifySul) { vm.saveSettings(s.copy(notifySul = it)) }
            SettingsCard("Notificar Baixada Santista", s.notifyBaixada) { vm.saveSettings(s.copy(notifyBaixada = it)) }
            SettingsCard("Só inscrições abertas", s.notifyOnlyOpen) { vm.saveSettings(s.copy(notifyOnlyOpen = it)) }
            SettingsCard("Só oportunidades aderentes/prioritárias", s.notifyOnlyRelevant) { vm.saveSettings(s.copy(notifyOnlyRelevant = it)) }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Perfil de prioridade", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("Separe por vírgulas. Essas palavras ajudam a decidir quais concursos novos merecem notificação.", color = AppMuted, fontSize = 12.sp)
                    OutlinedTextField(value = keywords, onValueChange = { keywords = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3, label = { Text("Palavras prioritárias") })
                    Button(onClick = { vm.saveSettings(s.copy(priorityKeywords = keywords)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Salvar perfil") }
                }
            }

            Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Saúde do monitor", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${vm.state.healthySources} de ${vm.state.sourceCount} fontes responderam na última coleta.", color = AppMuted)
                    vm.state.contestError?.let { Text("Feed de concursos: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    vm.state.alertError?.let { Text("Feed de alertas: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                    val failing = vm.state.sourceHealth.filterNot { it.ok }
                    if (failing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Fontes com falha", fontWeight = FontWeight.SemiBold)
                        failing.take(12).forEach { source ->
                            Text("• ${source.label}: ${source.error.ifBlank { "sem resposta" }}", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Versão instalada: ${BuildConfig.VERSION_NAME}", color = AppMuted)
                    Text("Última release detectada: ${latest ?: "não verificada"}", color = AppMuted)
                    if (updateAvailable) {
                        Button(onClick = { uri.openUri(RELEASES_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Icon(Icons.Filled.SystemUpdate, null); Spacer(Modifier.width(8.dp)); Text("Baixar atualização $latest")
                        }
                        Text("O Android exige sua confirmação para instalar APKs; o app não tenta contornar essa proteção.", color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                    OutlinedButton(onClick = { uri.openUri(RELEASES_URL) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Abrir releases") }
                    Button(onClick = vm::syncNow, enabled = !vm.state.syncing, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Verificar dados agora") }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), colors = CardDefaults.cardColors(containerColor = AppPanel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f)); Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), colors = CardDefaults.cardColors(containerColor = AppPanel), border = BorderStroke(1.dp, Color(0xFF2D2340))) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = AppMuted); Spacer(Modifier.width(10.dp)); Text(text, color = AppMuted)
        }
    }
}
