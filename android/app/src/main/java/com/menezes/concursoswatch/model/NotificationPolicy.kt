package com.menezes.concursoswatch.model

import java.text.Normalizer

object NotificationPolicy {
    fun shouldNotify(c: Contest, s: UserSettings): Boolean {
        if (s.notifyOnlyOpen && c.status !in setOf("open", "closing_soon")) return false
        if (!matchesRegion(c, s)) return false
        if (!s.notifyOnlyRelevant) return true

        val corpus = normalized(listOf(c.title, c.organization, c.city, c.area, c.education, c.type, c.source).joinToString(" "))
        val keywords = s.priorityKeywords.split(',').map(::normalized).filter { it.length >= 2 }
        val keywordMatch = keywords.any { it in corpus }
        val defaultMatch = c.priority >= 85 || normalized(c.area) in setOf("matematica", "mecatronica", "ti", "administrativo", "docencia") || normalized(c.education).contains("medio")
        return keywordMatch || defaultMatch
    }

    fun matchesRegion(c: Contest, s: UserSettings): Boolean {
        val city = normalized(c.city)
        return when {
            c.scope.equals("federal", true) && s.notifyFederal -> true
            (c.region.equals("SC", true) || c.uf.equals("SC", true)) && s.notifySantaCatarina -> true
            (c.region.equals("Sul", true) || c.uf.uppercase() in setOf("SC", "PR", "RS")) && s.notifySul -> true
            city in setOf("praia grande", "santos", "sao vicente", "cubatao", "guaruja") && s.notifyBaixada -> true
            else -> false
        }
    }

    fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().trim()
}
