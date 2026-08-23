package com.menezes.concursoswatch.model

data class Contest(
    val id: String,
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
)

data class AlertItem(
    val id: Int,
    val title: String,
    val body: String,
    val url: String,
    val createdAt: String,
    val priority: Int,
    val unread: Boolean = false,
)

data class SourceHealth(
    val id: String,
    val label: String,
    val ok: Boolean,
    val itemCount: Int,
    val checkedAt: String,
    val error: String,
)

data class SyncResult(
    val contestsOk: Boolean,
    val alertsOk: Boolean,
    val contestError: String? = null,
    val alertError: String? = null,
    val newContestIds: List<String> = emptyList(),
    val newAlertIds: List<Int> = emptyList(),
    val sourceHealth: List<SourceHealth> = emptyList(),
)

data class UserSettings(
    val notifyFederal: Boolean = true,
    val notifySantaCatarina: Boolean = true,
    val notifySul: Boolean = true,
    val notifyBaixada: Boolean = true,
    val notifyOnlyOpen: Boolean = true,
    val notifyOnlyRelevant: Boolean = true,
    val priorityKeywords: String = "matemática, mecatrônica, TI, administrativo, estágio, IFSP, TJSP, TJSC, IFSC, UFSC, São Bento do Sul",
)

enum class RegionFilter { ALL, FEDERAL, SC, SUL, BAIXADA }
enum class StatusFilter { ALL, OPEN, CLOSING_SOON, NEW }
