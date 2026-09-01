package com.menezes.concursoswatch.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContestFeedGuardTest {
    @Test
    fun rejectsInstitutionalNavigationEvenFromOfficialCourt() {
        assertFalse(ContestFeedGuard.accepts(contest("Institucional", "https://www.tjsc.jus.br/institucional")))
    }

    @Test
    fun rejectsProcurementNotices() {
        assertFalse(
            ContestFeedGuard.accepts(
                contest(
                    "AVISO DE LICITAÇÃO – PREGÃO ELETRÔNICO",
                    "https://example.gov.br/licitacoes/pregao/23",
                ),
            ),
        )
    }

    @Test
    fun keepsRealRecruitmentOpportunities() {
        assertTrue(
            ContestFeedGuard.accepts(
                contest(
                    "Concurso Público para Assistente Administrativo",
                    "https://example.gov.br/concursos/edital-02-2026",
                ),
            ),
        )
    }

    private fun contest(title: String, url: String) = Contest(
        id = url,
        title = title,
        organization = "Órgão",
        city = "",
        uf = "SC",
        region = "SC",
        scope = "estadual",
        type = "concurso público",
        education = "nível médio",
        area = "Administrativo",
        remuneration = "",
        vacancies = "",
        fee = "",
        startDate = "",
        endDate = "",
        status = "open",
        source = "oficial",
        url = url,
        editalUrl = url,
        firstSeen = "",
        lastSeen = "",
        priority = 50,
    )
}
