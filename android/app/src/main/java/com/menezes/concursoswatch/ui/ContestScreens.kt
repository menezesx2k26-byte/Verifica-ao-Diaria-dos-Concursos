package com.menezes.concursoswatch.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.RegionFilter
import com.menezes.concursoswatch.model.StatusFilter

@Composable
fun ContestListScreen(vm: AppViewModel, favoritesOnly: Boolean, onDetail: (Contest) -> Unit) {
    val list = vm.filteredContests(favoritesOnly)
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            ScreenHeader(
                if (favoritesOnly) "Favoritos" else "Concursos",
                if (favoritesOnly) "Salvos mesmo se saírem do feed ativo" else "Editais e processos seletivos estruturados",
            )
            if (!favoritesOnly) {
                OutlinedTextField(
                    value = vm.state.search,
                    onValueChange = vm::setSearch,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    placeholder = { Text("Cargo, órgão, cidade, área…") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
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
    val options = listOf(
        RegionFilter.ALL to "Todos",
        RegionFilter.FEDERAL to "Federal",
        RegionFilter.SC to "Santa Catarina",
        RegionFilter.SUL to "Sul",
        RegionFilter.BAIXADA to "SP · Baixada",
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = vm.state.regionFilter == value, onClick = { vm.setRegion(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun StatusFilters(vm: AppViewModel) {
    val options = listOf(
        StatusFilter.ALL to "Todos",
        StatusFilter.OPEN to "Inscrições abertas",
        StatusFilter.CLOSING_SOON to "Encerra em 7 dias",
        StatusFilter.NEW to "Novos",
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(selected = vm.state.statusFilter == value, onClick = { vm.setStatus(value) }, label = { Text(label) })
        }
    }
}

@Composable
fun ContestDetailScreen(vm: AppViewModel, contestId: String, onBack: () -> Unit) {
    LaunchedEffect(contestId) { if (contestId.isNotBlank()) vm.loadContest(contestId) }
    val c = vm.state.selectedContest?.takeIf { it.id == contestId }
    val uri = LocalUriHandler.current

    if (c == null) {
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                }
                EmptyCard("Carregando detalhes do concurso…")
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(12.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                Text("Detalhes", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            }
            Column(Modifier.padding(horizontal = 20.dp)) {
                StatusPill(c.status, c.active)
                Text(c.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Text(c.organization.ifBlank { c.source }, color = AppPurple, fontSize = 16.sp)
                Spacer(Modifier.height(18.dp))
                DetailRow("Local", listOf(c.city, c.uf).filter { it.isNotBlank() }.joinToString(" · "))
                DetailRow("Esfera", c.scope)
                DetailRow("Tipo", c.type)
                DetailRow("Escolaridade", c.education)
                DetailRow("Área", c.area)
                DetailRow("Remuneração", c.remuneration)
                DetailRow("Vagas / CR", c.vacancies)
                DetailRow("Taxa", c.fee)
                DetailRow("Inscrições", listOf(c.startDate, c.endDate).filter { it.isNotBlank() }.joinToString(" → "))
                DetailRow("Fonte", c.source)
                if (!c.active) Text("Este item não aparece mais no feed ativo. Foi mantido porque está salvo no aparelho.", color = AppMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = { c.editalUrl.ifBlank { c.url }.takeIf { it.isNotBlank() }?.let(uri::openUri) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = c.editalUrl.isNotBlank() || c.url.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text(" Abrir fonte oficial")
                }
                OutlinedButton(onClick = { vm.toggleFavorite(c) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null)
                    Text(if (c.favorite) " Remover dos favoritos" else " Salvar nos favoritos")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(label, color = AppMuted, fontSize = 12.sp)
        Text(value, fontSize = 16.sp)
    }
    HorizontalDivider()
}
