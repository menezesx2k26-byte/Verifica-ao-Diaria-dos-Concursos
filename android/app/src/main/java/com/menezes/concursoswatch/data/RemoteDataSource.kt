package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.SourceHealth
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val REPO = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos"
private const val ALERTS_API = "https://api.github.com/repos/$REPO/issues?state=open&labels=watch-alert&per_page=100&sort=created&direction=desc"
private const val CONTESTS_URL = "https://raw.githubusercontent.com/$REPO/main/state/new_contests.json"
private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"

class RemoteDataSource {
    data class ContestFeed(val items: List<Contest>, val health: List<SourceHealth>, val sourceCount: Int, val updatedAt: String)

    fun fetchContestFeed(): ContestFeed {
        val root = JSONObject(get(CONTESTS_URL))
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                val o = itemsJson.getJSONObject(i)
                add(Contest(
                    id = o.optString("id", o.optString("url")),
                    title = o.optString("title", "Oportunidade detectada"),
                    organization = o.optString("organization", o.optString("source")),
                    city = o.optString("city"), uf = o.optString("uf"), region = o.optString("region"), scope = o.optString("scope"),
                    type = o.optString("type"), education = o.optString("education"), area = o.optString("area"),
                    remuneration = o.optString("remuneration"), vacancies = o.optString("vacancies"), fee = o.optString("fee"),
                    startDate = o.optString("start_date"), endDate = o.optString("end_date"), status = o.optString("status", "detected"),
                    source = o.optString("source"), url = o.optString("url"), editalUrl = o.optString("edital_url", o.optString("url")),
                    firstSeen = o.optString("first_seen"), lastSeen = o.optString("last_seen"), priority = o.optInt("priority", 50)
                ))
            }
        }
        val healthJson = root.optJSONArray("source_health") ?: JSONArray()
        val health = buildList {
            for (i in 0 until healthJson.length()) {
                val o = healthJson.getJSONObject(i)
                add(SourceHealth(o.optString("id"), o.optString("label"), o.optBoolean("ok"), o.optInt("item_count"), o.optString("checked_at"), o.optString("error")))
            }
        }
        return ContestFeed(items, health, root.optInt("source_count", health.size), root.optString("updated_at"))
    }

    fun fetchAlerts(): List<AlertItem> {
        val json = JSONArray(get(ALERTS_API))
        return buildList {
            for (i in 0 until json.length()) {
                val o = json.getJSONObject(i)
                if (o.has("pull_request")) continue
                val title = o.optString("title")
                val body = o.optString("body")
                val score = priorityScore(title, body)
                if (score <= 0) continue
                add(AlertItem(o.getInt("number"), title, body, o.optString("html_url"), o.optString("created_at"), score))
            }
        }
    }

    fun latestReleaseVersion(): String? = runCatching { JSONObject(get(RELEASE_API)).optString("tag_name").removePrefix("android-v") }.getOrNull()

    private fun priorityScore(title: String, body: String): Int {
        val text = (title + " " + body).lowercase()
        if (listOf("documento alterado", "deep audit indispon", "fonte indispon", "heartbeat").any { it in text }) return 0
        var score = 0
        if (listOf("convoca", "nomea", "posse", "reclassifica", "exame admissional", "entrega de documentos").any { it in text }) score += 100
        if (listOf("classificação final", "classificacao final", "homolog", "resultado final", "atribuição", "atribuicao").any { it in text }) score += 70
        if (listOf("004/2024", "02/2026", "professor iii", "matemática", "matematica", "assistente-técnico", "assistente tecnico").any { it in text }) score += 50
        return score
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/vnd.github+json, application/json, text/plain")
        c.setRequestProperty("User-Agent", "ConcursosWatch-Android/2.0")
        val code = c.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
}
