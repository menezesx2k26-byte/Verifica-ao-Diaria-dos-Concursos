package com.menezes.concursoswatch.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
fun ScreenHeader(title: String, subtitle: String? = null, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = AppMuted, fontSize = 14.sp)
        }
        action?.invoke()
    }
}

@Composable
fun StatusPill(status: String, active: Boolean = true) {
    val (label, color) = when {
        !active -> "ARQUIVADO" to AppMuted
        status == "open" -> "ABERTO" to AppGreen
        status == "closing_soon" -> "ENCERRA LOGO" to Color(0xFFFFB74D)
        status == "closed" -> "ENCERRADO" to AppMuted
        status == "announced" -> "ANUNCIADO" to AppPurple
        else -> "DETECTADO" to AppPurple
    }
    Surface(color = color.copy(alpha = .15f), shape = RoundedCornerShape(50)) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
fun ContestCard(c: Contest, toggleFavorite: (Contest) -> Unit, onDetail: (Contest) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { onDetail(c) },
        colors = CardDefaults.cardColors(containerColor = if (c.unread) Color(0xFF1D152B) else AppPanel),
        border = if (c.unread) BorderStroke(1.dp, Color(0xFF5E3484)) else null,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    StatusPill(c.status, c.active)
                    Text(c.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                    Text(c.organization.ifBlank { c.source }, color = AppPurple, fontSize = 13.sp)
                }
                IconButton(
                    onClick = { toggleFavorite(c) },
                    modifier = Modifier.semantics { contentDescription = if (c.favorite) "Remover dos favoritos" else "Adicionar aos favoritos" },
                ) {
                    Icon(if (c.favorite) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = null, tint = if (c.favorite) Color(0xFFFFC857) else AppMuted)
                }
            }
            val meta = listOfNotNull(
                c.city.takeIf { it.isNotBlank() }, c.education.takeIf { it.isNotBlank() }, c.area.takeIf { it.isNotBlank() },
                c.remuneration.takeIf { it.isNotBlank() }, c.vacancies.takeIf { it.isNotBlank() }?.let { "$it vagas/CR" },
                c.endDate.takeIf { it.isNotBlank() }?.let { "até $it" },
            )
            if (meta.isNotEmpty()) Text(meta.joinToString(" • "), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun AlertCard(a: AlertItem, markRead: () -> Unit) {
    val uri = LocalUriHandler.current
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable {
            markRead()
            if (a.url.isNotBlank()) uri.openUri(a.url)
        },
        colors = CardDefaults.cardColors(containerColor = if (a.unread) Color(0xFF20162E) else AppPanel),
        border = if (a.priority >= 100) BorderStroke(1.dp, AppPurple) else null,
    ) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.NotificationsActive, contentDescription = null, tint = if (a.priority >= 100) AppPurple else AppMuted)
                Spacer(Modifier.width(8.dp))
                Text(a.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (a.unread) Badge { Text("novo") }
            }
            if (a.body.isNotBlank()) Text(a.body.replace(Regex("\\s+"), " ").take(260), color = AppMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
fun EmptyCard(text: String) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = AppPanel),
        border = BorderStroke(1.dp, Color(0xFF2D2340)),
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = AppMuted)
            Spacer(Modifier.width(10.dp))
            Text(text, color = AppMuted)
        }
    }
}
