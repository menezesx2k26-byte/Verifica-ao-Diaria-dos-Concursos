package com.menezes.concursoswatch.data

import com.menezes.concursoswatch.BuildConfig
import com.menezes.concursoswatch.model.AlertItem
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.ContestFeedGuard
import com.menezes.concursoswatch.model.SourceHealth
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val REPO = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos"
private const val GITHUB_ALERTS = "https://api.github.com/repos/$REPO/issues?state=open&labels=watch-alert&per_page=100&sort=created&direction=desc"
private const val GITHUB_CONTESTS = "https://raw.githubusercontent.com/$REPO/main/state/new_contests.json"
private const val GITHUB_RELEASE = "https://api.github.com/repos/$REPO/releases/latest"
private const val RUNTIME_URL = "https://raw.githubusercontent.com/$REPO/main/config/runtime.json"

class RemoteDataSource {
    data class ContestFeed(val items: List<Contest>, val health: List<SourceHealth>, val sourceCount: Int, val updatedAt: String)
    @Volatile private var discoveredApiBase: String? = null

    fun fetchContestFeed(): ContestFeed {
        val root = JSONObject(fetchWithFallback("/api/v1/contests", GITHUB_CONTESTS))
        val itemsJson = root.optJSONArray("items") ?: root.optJSONArray("contests") ?: JSONArray()
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                val o = itemsJson.getJSONObject(i)
                add(Contest(
                    id=o.optString("id",o.optString("url")), title=o.optString("title","Oportunidade detectada"),
                    organization=o.optString("organization",o.optString("source")), city=o.optString("city"), uf=o.optString("uf"),
                    region=o.optString("region"), scope=o.optString("scope"), type=o.optString("type"), education=o.optString("education"),
                    area=o.optString("area"), remuneration=o.optString("remuneration"), vacancies=o.optString("vacancies"), fee=o.optString("fee"),
                    startDate=o.optString("start_date"), endDate=o.optString("end_date"), status=o.optString("status","detected"),
                    source=o.optString("source"), url=o.optString("url"), editalUrl=o.optString("edital_url",o.optString("url")),
                    firstSeen=o.optString("first_seen"), lastSeen=o.optString("last_seen"), priority=o.optInt("priority",50), active=o.optBoolean("active",true),
                ))
            }
        }
        val healthJson = root.optJSONArray("source_health") ?: root.optJSONArray("sources") ?: JSONArray()
        val health = buildList {
            for (i in 0 until healthJson.length()) {
                val o = healthJson.getJSONObject(i); val legacyOk = o.optBoolean("ok", false)
                add(SourceHealth(
                    id=o.optString("id"), label=o.optString("label"),
                    httpOk=if(o.has("http_ok"))o.optBoolean("http_ok")else legacyOk,
                    parserOk=if(o.has("parser_ok"))o.optBoolean("parser_ok")else legacyOk,
                    semanticOk=if(o.has("semantic_ok"))o.optBoolean("semantic_ok")else legacyOk,
                    itemCount=o.optInt("item_count"), expectedMin=o.optInt("expected_min"), checkedAt=o.optString("checked_at"),
                    lastSuccessAt=o.optString("last_success_at",o.optString("checked_at")), fingerprint=o.optString("fingerprint"),
                    scanStatus=o.optString("scan_status",if(legacyOk)"NO_CHANGE_CONFIRMED" else "UNKNOWN"), error=o.optString("error"),
                ))
            }
        }
        val safeItems = items.filter { it.active && ContestFeedGuard.accepts(it) }
        return ContestFeed(safeItems, health, root.optInt("source_count",health.size), root.optString("updated_at"))
    }

    fun fetchAlerts(): List<AlertItem> {
        val raw = fetchWithFallback("/api/v1/alerts", GITHUB_ALERTS)
        val array = if (raw.trimStart().startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("items") ?: JSONArray()
        return buildList {
            for (i in 0 until array.length()) {
                val o=array.getJSONObject(i); if(o.has("pull_request")) continue
                val title=o.optString("title"); val body=o.optString("body")
                val score=if(o.has("priority"))o.optInt("priority")else priorityScore(title,body)
                if(score<=0)continue
                add(AlertItem(o.optInt("id",o.optInt("number")),title,body,o.optString("url",o.optString("html_url")),o.optString("created_at"),score))
            }
        }
    }

    fun latestReleaseVersion(): String? = runCatching {
        val root=JSONObject(fetchWithFallback("/api/v1/releases/latest",GITHUB_RELEASE))
        root.optString("version",root.optString("tag_name")).removePrefix("android-v")
    }.getOrNull()?.takeIf{it.isNotBlank()}

    private fun priorityScore(title:String,body:String):Int{
        val text=(title+" "+body).lowercase(); if(listOf("documento alterado","deep audit indispon","fonte indispon","heartbeat").any{it in text})return 0
        var score=0; if(listOf("convoca","nomea","posse","reclassifica","exame admissional","entrega de documentos").any{it in text})score+=100
        if(listOf("classificação final","classificacao final","homolog","resultado final","atribuição","atribuicao").any{it in text})score+=70
        return score
    }

    private fun apiBase(): String {
        val built=BuildConfig.API_BASE_URL.trim().trimEnd('/'); if(built.isNotBlank()) return built
        discoveredApiBase?.let { return it }
        val discovered=runCatching { JSONObject(get(RUNTIME_URL)).optString("cloudflare_url").trim().trimEnd('/') }.getOrDefault("")
        discoveredApiBase=discovered
        return discovered
    }

    private fun fetchWithFallback(apiPath:String,fallback:String):String{
        val base=apiBase(); if(base.isNotBlank()) runCatching{return get("$base$apiPath")}
        return get(fallback)
    }

    private fun get(url:String):String{
        val c=URL(url).openConnection() as HttpURLConnection
        try{
            c.connectTimeout=15_000;c.readTimeout=20_000;c.requestMethod="GET"
            c.setRequestProperty("Accept","application/vnd.github+json, application/json, text/plain")
            c.setRequestProperty("User-Agent","ConcursosWatch-Android/3.0")
            val code=c.responseCode;if(code !in 200..299)throw IllegalStateException("HTTP $code em $url")
            return c.inputStream.bufferedReader().use{it.readText()}
        }finally{c.disconnect()}
    }
}
