package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
fun ContestListScreen(
    vm: AppViewModel,
    favoritesOnly: Boolean,
    onDetail: (Contest) -> Unit,
) {
    val list = vm.filteredContests(favoritesOnly)

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            ScreenHeader(
                title = if (favoritesOnly) "Favoritos" else "Concursos",
                subtitle = if (favoritesOnly) "O que você decidiu acompanhar mais de perto" else "Editais e processos seletivos encontrados nas fontes oficiais",
            )

            if (!favoritesOnly) {
                OutlinedTextField(
                    value = vm.state.search,
                    onValueChange = vm::setSearch,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = AppMuted) },
                    placeholder = { Text("Cargo, órgão, cidade ou área") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppPurple,
                        unfocusedBorderColor = AppDivider,
                        focusedContainerColor = AppPanel,
                        unfocusedContainerColor = AppPanel,
                    ),
                )

                FilterLabel("Região")
                RegionFilters(vm)
                FilterLabel("Situação")
                StatusFilters(vm)

                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${list.size} resultado(s)",
                        color = AppMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(onClick = vm::markAllContestsRead) { Text("Marcar como lidos") }
                }
            }
        }

        if (list.isEmpty()) {
            item {
                EmptyCard(
                    if (favoritesOnly) "Nenhum favorito ainda. Toque na estrela de um concurso para salvar."
                    else "Nenhum concurso combina com os filtros atuais.",
                )
            }
        } else {
            items(list, key = { it.id }) { ContestCard(it, vm::toggleFavorite, onDetail) }
        }
    }
}

@Composable
private fun FilterLabel(text: String) {
    Text(
        text.uppercase(),
        color = AppMuted2,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
    )
}

@Composable
private fun RegionFilters(vm: AppViewModel) {
    val options = listOf(
        RegionFilter.ALL to "Todos",
        RegionFilter.FEDERAL to "Federal",
        RegionFilter.SC to "Santa Catarina",
        RegionFilter.SUL to "Sul",
        RegionFilter.BAIXADA to "Baixada",
    )
    FilterRow {
        options.forEach { (value, label) ->
            AppFilterChip(vm.state.regionFilter == value, label) { vm.setRegion(value) }
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
    FilterRow {
        options.forEach { (value, label) ->
            AppFilterChip(vm.state.statusFilter == value, label) { vm.setStatus(value) }
        }
    }
}

@Composable
private fun FilterRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
private fun AppFilterChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(999.dp),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = AppPanel,
            labelColor = AppMuted,
            selectedContainerColor = AppPurpleSoft,
            selectedLabelColor = AppText,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = AppDivider,
            selectedBorderColor = AppPurple.copy(alpha = 0.45f),
        ),
    )
}

@Composable
fun ContestDetailScreen(vm: AppViewModel, contestId: String, onBack: () -> Unit) {
    LaunchedEffect(contestId) {
        if (contestId.isNotBlank()) vm.loadContest(contestId)
    }
    val c = vm.state.selectedContest?.takeIf { it.id == contestId }
    val uri = LocalUriHandler.current

    if (c == null) {
        LazyColumn {
            item {
                Row(Modifier.fillMaxWidth().padding(12.dp)) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
                }
                EmptyCard("Carregando detalhes…")
            }
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 34.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar") }
            }

            Column(Modifier.padding(horizontal = 20.dp)) {
                StatusPill(c.status, c.active)
                Text(
                    c.title,
                    style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    c.organization.ifBlank { c.source },
                    color = AppPurple,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )

                if (!c.active) {
                    Card(
                        Modifier.fillMaxWidth().padding(top = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = AppPanel2),
                        border = BorderStroke(1.dp, AppDivider),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            "Este concurso não aparece mais no feed ativo, mas foi preservado porque está salvo no aparelho.",
                            color = AppMuted,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                DetailsCard(c)

                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { c.editalUrl.ifBlank { c.url }.takeIf { it.isNotBlank() }?.let(uri::openUri) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = c.editalUrl.isNotBlank() || c.url.isNotBlank(),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Text("  Abrir fonte oficial")
                }
                OutlinedButton(
                    onClick = { vm.toggleFavorite(c) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null)
                    Text(if (c.favorite) "  Remover dos favoritos" else "  Salvar nos favoritos")
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(c: Contest) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
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
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(label.uppercase(), color = AppMuted2, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp)
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 3.dp))
    }
}
