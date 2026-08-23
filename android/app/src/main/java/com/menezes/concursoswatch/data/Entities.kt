package com.menezes.concursoswatch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contests")
data class ContestEntity(
    @PrimaryKey val id: String,
    val title: String,
    val organization: String,
    val city: String,
    val uf: String,
    val region: String,
    val scope: String,
    val type: String,
    val education: String,
    val area: String,
    val remuneration: String,
    val vacancies: String,
    val fee: String,
    val startDate: String,
    val endDate: String,
    val status: String,
    val source: String,
    val url: String,
    val editalUrl: String,
    val firstSeen: String,
    val lastSeen: String,
    val priority: Int,
    val favorite: Boolean = false,
    val unread: Boolean = false,
    val active: Boolean = true,
    val syncGeneration: Long = 0L,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val body: String,
    val url: String,
    val createdAt: String,
    val priority: Int,
    val unread: Boolean = false,
)

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val id: String,
    val label: String,
    val httpOk: Boolean,
    val parserOk: Boolean,
    val semanticOk: Boolean,
    val itemCount: Int,
    val expectedMin: Int,
    val checkedAt: String,
    val lastSuccessAt: String,
    val fingerprint: String,
    val error: String,
)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)
