package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest

@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = AppText)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppMuted,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        action?.invoke()
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = AppText)
            if (!subtitle.isNullOrBlank()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = AppMuted, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Surface(
                modifier = Modifier.clickable(onClick = onAction),
                color = Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    actionLabel,
                    color = AppPurple,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun StatusPill(status: String, active: Boolean = true) {
    val (label, color) = when {
        !active -> "ARQUIVADO" to AppMuted
        status == "open" -> "INSCRIÇÕES ABERTAS" to AppGreen
        status == "closing_soon" -> "ENCERRA LOGO" to AppAmber
        status == "closed" -> "ENCERRADO" to AppMuted
        status == "announced" -> "ANUNCIADO" to AppPurple
        else -> "MONITORADO" to AppPurple
    }
    Surface(
        color = color.copy(alpha = 0.13f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.22f)),
    ) {
        Text(
            label,
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun ContestCard(
    c: Contest,
    toggleFavorite: (Contest) -> Unit,
    onDetail: (Contest) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onDetail(c) },
        colors = CardDefaults.cardColors(containerColor = if (c.unread) Color(0xFF181221) else AppPanel),
        border = BorderStroke(1.dp, if (c.unread) AppPurple.copy(alpha = 0.38f) else AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, bottom = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusPill(c.status, c.active)
                        if (c.unread) {
                            Text(
                                "NOVO",
                                color = AppPurple,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.7.sp,
                                modifier = Modifier.padding(start = 9.dp),
                            )
                        }
                    }
                    Text(
                        c.title,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppText,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 11.dp, end = 8.dp),
                    )
                    Text(
                        c.organization.ifBlank { c.source },
                        color = AppPurple,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                    )
                }
                IconButton(
                    onClick = { toggleFavorite(c) },
                    modifier = Modifier.semantics {
                        contentDescription = if (c.favorite) "Remover dos favoritos" else "Adicionar aos favoritos"
                    },
                ) {
                    Icon(
                        if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = if (c.favorite) AppAmber else AppMuted,
                    )
                }
            }

            val line1 = listOfNotNull(
                c.city.takeIf { it.isNotBlank() },
                c.uf.takeIf { it.isNotBlank() && !c.city.contains(it, ignoreCase = true) },
                c.education.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (line1.isNotBlank()) {
                Text(line1, color = AppMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp, end = 16.dp))
            }

            val line2 = listOfNotNull(
                c.remuneration.takeIf { it.isNotBlank() },
                c.vacancies.takeIf { it.isNotBlank() }?.let { if (it.equals("CR", true)) "Cadastro reserva" else "$it vagas/CR" },
                c.endDate.takeIf { it.isNotBlank() }?.let { "até $it" },
            ).joinToString("  ·  ")
            if (line2.isNotBlank()) {
                Text(line2, color = AppText.copy(alpha = 0.86f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp, end = 16.dp))
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, end = 10.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Ver detalhes", color = AppPurple, style = MaterialTheme.typography.labelLarge)
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AppPurple, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
fun AlertCard(a: AlertItem, markRead: () -> Unit) {
    val uri = LocalUriHandler.current
    val urgent = a.priority >= 100
    val accent = if (urgent) AppAmber else AppPurple

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable {
                markRead()
                if (a.url.isNotBlank()) uri.openUri(a.url)
            },
        colors = CardDefaults.cardColors(containerColor = if (a.unread) Color(0xFF191321) else AppPanel),
        border = BorderStroke(1.dp, if (a.unread) accent.copy(alpha = 0.38f) else AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Surface(
                color = accent.copy(alpha = 0.12f),
                shape = RoundedCornerShape(13.dp),
            ) {
                Icon(
                    Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.padding(10.dp).size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (urgent) "PRIORIDADE" else "ATUALIZAÇÃO",
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.7.sp,
                    )
                    if (a.unread) {
                        Text("  •  NOVO", color = AppPurple, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    a.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppText,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (a.body.isNotBlank()) {
                    Text(
                        a.body.replace(Regex("\\s+"), " ").take(280),
                        color = AppMuted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, AppDivider),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(
            text,
            color = AppMuted,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(18.dp),
        )
    }
}
