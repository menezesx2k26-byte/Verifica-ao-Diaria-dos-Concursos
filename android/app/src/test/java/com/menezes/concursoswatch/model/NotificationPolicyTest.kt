package com.menezes.concursoswatch.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPolicyTest {
    private fun contest(
        scope: String = "municipal",
        region: String = "SP",
        uf: String = "SP",
        city: String = "Praia Grande",
        area: String = "Administrativo",
        status: String = "open",
        priority: Int = 50,
        title: String = "Assistente administrativo",
    ) = Contest(
        id="1", title=title, organization="Órgão", city=city, uf=uf, region=region, scope=scope,
        type="concurso público", education="nível médio", area=area, remuneration="", vacancies="", fee="",
        startDate="", endDate="", status=status, source="fonte", url="https://example.gov.br", editalUrl="https://example.gov.br",
        firstSeen="", lastSeen="", priority=priority,
    )

    @Test fun federalMatchesWhenEnabled() {
        assertTrue(NotificationPolicy.shouldNotify(contest(scope="federal", city="Brasília", region="Brasil", uf="DF"), UserSettings()))
    }

    @Test fun closedIsRejectedWhenOnlyOpen() {
        assertFalse(NotificationPolicy.shouldNotify(contest(status="closed"), UserSettings(notifyOnlyOpen=true)))
    }

    @Test fun santaCatarinaMatchesSouthProfile() {
        assertTrue(NotificationPolicy.matchesRegion(contest(region="SC", uf="SC", city="Florianópolis"), UserSettings()))
    }

    @Test fun keywordMakesLowPriorityItemRelevant() {
        val c = contest(city="Joinville", region="SC", uf="SC", area="", priority=20, title="Estágio em Matemática")
        assertTrue(NotificationPolicy.shouldNotify(c, UserSettings(priorityKeywords="matemática")))
    }

    @Test fun unrelatedLowPriorityItemIsRejected() {
        val c = contest(city="Joinville", region="SC", uf="SC", area="", priority=20, title="Médico veterinário")
            .copy(education="nível superior")
        assertFalse(NotificationPolicy.shouldNotify(c, UserSettings(priorityKeywords="matemática, mecatrônica")))
    }
}
