package com.menezes.concursoswatch.data

import androidx.room.ColumnInfo
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
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String,
    val status: String,
    val source: String,
    val url: String,
    @ColumnInfo(name = "edital_url") val editalUrl: String,
    @ColumnInfo(name = "first_seen") val firstSeen: String,
    @ColumnInfo(name = "last_seen") val lastSeen: String,
    val priority: Int,
    val favorite: Boolean = false,
    val unread: Boolean = false,
    val active: Boolean = true,
    @ColumnInfo(name = "sync_generation") val syncGeneration: Long = 0L,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey val id: Int,
    val title: String,
    val body: String,
    val url: String,
    @ColumnInfo(name = "created_at") val createdAt: String,
    val priority: Int,
    val unread: Boolean = false,
)

@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey val id: String,
    val label: String,
    @ColumnInfo(name = "http_ok") val httpOk: Boolean,
    @ColumnInfo(name = "parser_ok") val parserOk: Boolean,
    @ColumnInfo(name = "semantic_ok") val semanticOk: Boolean,
    @ColumnInfo(name = "item_count") val itemCount: Int,
    @ColumnInfo(name = "expected_min") val expectedMin: Int,
    @ColumnInfo(name = "checked_at") val checkedAt: String,
    @ColumnInfo(name = "last_success_at") val lastSuccessAt: String,
    val fingerprint: String,
    @ColumnInfo(name = "scan_status") val scanStatus: String,
    val error: String,
)

@Entity(tableName = "meta")
data class MetaEntity(@PrimaryKey val key: String, val value: String)
