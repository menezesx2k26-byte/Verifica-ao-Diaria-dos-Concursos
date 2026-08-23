package com.menezes.concursoswatch

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val PREFS = "concursos_watch"
private const val KEY_LAST_ALERT = "last_useful_alert"
private const val KEY_LAST_SYNC = "last_sync"
private const val KEY_LAST_TITLE = "last_title"
private const val KEY_LAST_URL = "last_url"
private const val KEY_NEW_CONTESTS_CACHE = "new_contests_cache"
private const val KEY_KNOWN_CONTEST_URLS = "known_contest_urls"
private const val KEY_CONTESTS_BASELINED = "contests_baselined"
private const val KEY_ALERT_COUNT = "alert_count"
private const val KEY_PRIORITY_COUNT = "priority_count"
private const val KEY_NEW_COUNT = "new_count"
private const val KEY_SOURCE_COUNT = "source_count"
private const val ALERT_CHANNEL = "concursos_alertas_importantes"
private const val NEW_CHANNEL = "concursos_novos"
private const val UNIQUE_WORK = "concursos_watch_periodic_v3"
private const val REPO = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos"
private const val REPO_URL = "https://github.com/$REPO"
private const val ALERTS_API = "https://api.github.com/repos/$REPO/issues?state=open&labels=watch-alert&per_page=60&sort=created&direction=desc"
private const val NEW_CONTESTS_URL = "https://raw.githubusercontent.com/$REPO/main/state/new_contests.json"

private val BG = Color.rgb(7, 10, 15)
private val PANEL = Color.rgb(17, 22, 29)
private val PANEL_2 = Color.rgb(22, 27, 36)
private val TEXT = Color.rgb(245, 246, 250)
private val MUTED = Color.rgb(164, 168, 180)
private val PURPLE = Color.rgb(176, 92, 255)
private val PURPLE_DARK = Color.rgb(72, 35, 111)
private val GREEN = Color.rgb(82, 214, 100)

class MainActivity : Activity() {
    private lateinit var syncText: TextView
    private lateinit var nextText: TextView
    private lateinit var latest: TextView
    private lateinit var contestsContainer: LinearLayout
    private lateinit var alertCount: TextView
    private lateinit var priorityCount: TextView
    private lateinit var newCount: TextView
    private lateinit var sourceCount: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        createChannels(this)
        requestNotificationPermissionIfNeeded()
        buildUi()
        scheduleMonitor()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::syncText.isInitialized) refreshUi()
    }

    private fun buildUi() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(18), dp(18), dp(18), dp(22))
        }

        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(this).apply {
            text = "Concursos Watch"
            textSize = 30f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(TextView(this).apply {
            text = "⚙"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            setOnClickListener { openRepo() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(titleRow)

        page.addView(TextView(this).apply {
            text = "Só o que merece sua atenção"
            textSize = 17f
            setTextColor(PURPLE)
            setPadding(0, 0, 0, dp(20))
        })

        syncText = TextView(this).apply {
            textSize = 14f
            setTextColor(MUTED)
        }
        nextText = TextView(this).apply {
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(5), 0, dp(18))
        }
        page.addView(syncText)
        page.addView(nextText)

        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        alertCount = statCard(stats, "◉", "Alertas")
        priorityCount = statCard(stats, "★", "Prioritários")
        newCount = statCard(stats, "✦", "Novos")
        sourceCount = statCard(stats, "▦", "Fontes")
        page.addView(stats)
        page.addView(space(18))

        page.addView(sectionCard(
            icon = "◇",
            title = "Alertas importantes",
            description = "Convocação, nomeação, posse, classificação/homologação relevante, reclassificação e eventos prioritários. Ruído técnico fica fora daqui.",
            bodyBuilder = { container ->
                latest = TextView(this).apply {
                    textSize = 15f
                    setTextColor(MUTED)
                    setPadding(dp(16), dp(16), dp(16), dp(16))
                    background = rounded(Color.rgb(23, 21, 36), dp(14), PURPLE_DARK, 1)
                }
                container.addView(latest)
            }
        ))
        page.addView(space(14))

        page.addView(sectionCard(
            icon = "✣",
            title = "Concursos novos",
            description = "Editais e processos seletivos recém-detectados em fontes oficiais. Resultados, gabaritos e convocações não entram nesta lista.",
            bodyBuilder = { container ->
                contestsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                container.addView(contestsContainer)
            }
        ))
        page.addView(space(16))

        val checkNow = Button(this).apply {
            text = "⟳   VERIFICAR AGORA"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = rounded(Color.rgb(102, 35, 190), dp(14))
            setOnClickListener {
                WorkManager.getInstance(this@MainActivity).enqueue(OneTimeWorkRequestBuilder<AlertWorker>().build())
                text = "⟳   VERIFICANDO…"
                isEnabled = false
                Handler(Looper.getMainLooper()).postDelayed({
                    text = "⟳   VERIFICAR AGORA"
                    isEnabled = true
                    refreshUi()
                }, 4500)
            }
        }
        page.addView(checkNow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(58)))
        page.addView(space(10))
        page.addView(Button(this).apply {
            text = "◉   ABRIR MONITOR NO GITHUB"
            textSize = 15f
            setTextColor(TEXT)
            isAllCaps = false
            background = rounded(PANEL_2, dp(14))
            setOnClickListener { openRepo() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)))

        page.addView(space(22))
        val filterHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        filterHeader.addView(TextView(this).apply {
            text = "Filtros rápidos"
            textSize = 18f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        filterHeader.addView(TextView(this).apply {
            text = "Ver todos  ›"
            textSize = 14f
            setTextColor(PURPLE)
        })
        page.addView(filterHeader)
        page.addView(space(10))

        val chipsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Todos", "Federal", "Santa Catarina", "Sul", "SP - Baixada").forEachIndexed { index, label ->
            chipsRow.addView(filterChip(label, index == 0))
            chipsRow.addView(spaceW(8))
        }
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipsRow)
        })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(BG)
            addView(page)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottomNav())
        setContentView(root)
    }

    private fun statCard(parent: LinearLayout, icon: String, label: String): TextView {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(42, 25, 69), Color.rgb(24, 20, 44))).apply {
                cornerRadius = dp(14).toFloat()
            }
        }
        val value = TextView(this).apply {
            text = "—"
            textSize = 23f
            setTextColor(PURPLE)
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(TextView(this).apply {
            text = icon
            textSize = 20f
            setTextColor(PURPLE)
            gravity = Gravity.CENTER
        })
        card.addView(value)
        card.addView(TextView(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.rgb(210, 182, 239))
            gravity = Gravity.CENTER
        })
        parent.addView(card, LinearLayout.LayoutParams(0, dp(100), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        return value
    }

    private fun sectionCard(icon: String, title: String, description: String, bodyBuilder: (LinearLayout) -> Unit): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(PANEL, dp(16), Color.rgb(35, 41, 52), 1)
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(TextView(this).apply { text = icon; textSize = 23f; setTextColor(PURPLE) })
        head.addView(TextView(this).apply {
            text = title
            textSize = 22f
            setTextColor(TEXT)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        card.addView(head)
        card.addView(TextView(this).apply {
            text = description
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(10), 0, dp(14))
            setLineSpacing(0f, 1.18f)
        })
        bodyBuilder(card)
        return card
    }

    private fun filterChip(label: String, selected: Boolean) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(if (selected) TEXT else Color.rgb(221, 223, 230))
        setPadding(dp(18), 0, dp(18), 0)
        background = rounded(if (selected) Color.rgb(42, 25, 69) else PANEL_2, dp(28))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(46))
    }

    private fun bottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(10, 13, 18))
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }
        listOf("⌂\nInício", "◉\nAlertas", "✦\nNovos", "▽\nFiltros", "⚙\nConfig.").forEachIndexed { index, label ->
            nav.addView(TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(if (index == 0) PURPLE else MUTED)
                if (index == 0) background = rounded(Color.rgb(33, 24, 52), dp(12))
            }, LinearLayout.LayoutParams(0, dp(62), 1f))
        }
        return nav
    }

    private fun refreshUi() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sync = prefs.getLong(KEY_LAST_SYNC, 0L)
        syncText.text = if (sync == 0L) "⟳  Última sincronização: Nunca" else {
            val whenText = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(sync))
            "⟳  Última sincronização: $whenText"
        }
        nextText.text = "Próxima verificação automática: aproximadamente 15 min"
        alertCount.text = prefs.getInt(KEY_ALERT_COUNT, 0).toString()
        priorityCount.text = prefs.getInt(KEY_PRIORITY_COUNT, 0).toString()
        newCount.text = prefs.getInt(KEY_NEW_COUNT, 0).toString()
        val sources = prefs.getInt(KEY_SOURCE_COUNT, 0)
        sourceCount.text = if (sources > 0) sources.toString() else "—"

        val title = prefs.getString(KEY_LAST_TITLE, null)
        val url = prefs.getString(KEY_LAST_URL, null)
        latest.text = if (title.isNullOrBlank()) "◌   Nenhum alerta importante novo neste aparelho." else "$title\n\n${url.orEmpty()}"
        renderNewContests(prefs.getString(KEY_NEW_CONTESTS_CACHE, null))
    }

    private fun renderNewContests(raw: String?) {
        contestsContainer.removeAllViews()
        if (raw.isNullOrBlank()) {
            contestsContainer.addView(emptyBox("⟳   Feed ainda não sincronizado."))
            return
        }
        try {
            val json = JSONObject(raw)
            val items = json.optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) {
                contestsContainer.addView(emptyBox("◌   Nenhum concurso novo detectado."))
                return
            }
            for (i in 0 until minOf(items.length(), 8)) {
                val item = items.getJSONObject(i)
                val title = item.optString("title", "Novo concurso/processo seletivo")
                val city = item.optString("city", "")
                val source = item.optString("source", "")
                val region = item.optString("region", "")
                val url = item.optString("url", REPO_URL)
                val row = TextView(this).apply {
                    text = buildString {
                        append(title)
                        val meta = listOf(city, source, region).filter { it.isNotBlank() }
                        if (meta.isNotEmpty()) append("\n" + meta.joinToString(" • "))
                    }
                    textSize = 14f
                    setTextColor(TEXT)
                    setPadding(dp(14), dp(13), dp(14), dp(13))
                    background = rounded(Color.rgb(23, 21, 36), dp(12), PURPLE_DARK, 1)
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                contestsContainer.addView(row)
                contestsContainer.addView(space(8))
            }
        } catch (_: Exception) {
            contestsContainer.addView(emptyBox("!   Não foi possível ler o feed. Toque em Verificar agora."))
        }
    }

    private fun emptyBox(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 15f
        setTextColor(MUTED)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.rgb(23, 21, 36), dp(14), PURPLE_DARK, 1)
    }

    private fun scheduleMonitor() {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<AlertWorker>().build())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun openRepo() = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
    private fun rounded(fill: Int, radius: Int, stroke: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != null && strokeWidth > 0) setStroke(dp(strokeWidth), stroke)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun space(height: Int = 14): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun spaceW(width: Int): View = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(width), 1) }
}

class AlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            syncImportantAlerts(prefs)
            syncNewContests(prefs)
            prefs.edit().putLong(KEY_LAST_SYNC, System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun syncImportantAlerts(prefs: android.content.SharedPreferences) {
        val all = fetchIssues()
        val useful = all.filter(::isUsefulAlert)
        val priority = useful.count { normalized(it.title + " " + it.body).contains("prioridade") }
        prefs.edit().putInt(KEY_ALERT_COUNT, useful.size).putInt(KEY_PRIORITY_COUNT, priority).apply()
        val previous = prefs.getInt(KEY_LAST_ALERT, 0)
        val maxNumber = useful.maxOfOrNull { it.number } ?: previous
        if (previous == 0) {
            prefs.edit().putInt(KEY_LAST_ALERT, maxNumber).apply()
            return
        }
        useful.filter { it.number > previous }.sortedBy { it.number }.forEach { issue ->
            notifyItem(applicationContext, ALERT_CHANNEL, issue.number, issue.title, issue.body, issue.url)
            prefs.edit().putString(KEY_LAST_TITLE, issue.title).putString(KEY_LAST_URL, issue.url).apply()
        }
        prefs.edit().putInt(KEY_LAST_ALERT, maxNumber).apply()
    }

    private fun syncNewContests(prefs: android.content.SharedPreferences) {
        val raw = fetchText(NEW_CONTESTS_URL)
        val json = JSONObject(raw)
        val items = json.optJSONArray("items") ?: JSONArray()
        val sourceCount = json.optInt("source_count", json.optInt("sources_count", 0))
        prefs.edit().putString(KEY_NEW_CONTESTS_CACHE, raw).putInt(KEY_NEW_COUNT, items.length()).putInt(KEY_SOURCE_COUNT, sourceCount).apply()

        val currentUrls = mutableSetOf<String>()
        val byUrl = mutableMapOf<String, JSONObject>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val url = item.optString("url", "")
            if (url.isNotBlank()) { currentUrls += url; byUrl[url] = item }
        }
        val baselineDone = prefs.getBoolean(KEY_CONTESTS_BASELINED, false)
        val known = prefs.getStringSet(KEY_KNOWN_CONTEST_URLS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!baselineDone) {
            prefs.edit().putStringSet(KEY_KNOWN_CONTEST_URLS, currentUrls).putBoolean(KEY_CONTESTS_BASELINED, true).apply()
            return
        }
        (currentUrls - known).take(8).forEachIndexed { index, url ->
            val item = byUrl[url] ?: return@forEachIndexed
            val title = item.optString("title", "Novo concurso/processo seletivo")
            val body = listOf(item.optString("city", ""), item.optString("source", ""), item.optString("region", "")).filter { it.isNotBlank() }.joinToString(" • ")
            notifyItem(applicationContext, NEW_CHANNEL, 900000 + index + url.hashCode().ushr(1), title, body, url)
        }
        known += currentUrls
        prefs.edit().putStringSet(KEY_KNOWN_CONTEST_URLS, known).apply()
    }

    private fun fetchIssues(): List<AlertIssue> {
        val json = JSONArray(fetchText(ALERTS_API))
        val result = mutableListOf<AlertIssue>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.has("pull_request")) continue
            result += AlertIssue(item.getInt("number"), item.optString("title", "Novo alerta"), item.optString("body", ""), item.optString("html_url", REPO_URL))
        }
        return result
    }

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.requestMethod = "GET"
        c.setRequestProperty("Accept", "application/vnd.github+json, application/json, text/plain")
        c.setRequestProperty("User-Agent", "ConcursosWatch-Android/1.3")
        val code = c.responseCode
        if (code !in 200..299) error("HTTP $code")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        return body
    }
}

data class AlertIssue(val number: Int, val title: String, val body: String, val url: String)

private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase()

private fun isUsefulAlert(issue: AlertIssue): Boolean {
    val text = normalized(issue.title + " " + issue.body)
    if ("prioridade" in text) return true
    if ("documento alterado" in text || "deep audit indisponivel" in text || "fonte indisponivel" in text) return false
    return listOf("convoca", "nomea", "posse", "classificacao final", "homolog", "reclassifica", "resultado final", "atribuicao", "exame admissional", "entrega de documentos", "desistencia").any { it in text }
}

private fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "Alertas importantes", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
        manager.createNotificationChannel(NotificationChannel(NEW_CHANNEL, "Concursos novos", NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    }
}

private fun notifyItem(context: Context, channel: String, id: Int, title: String, body: String, url: String) {
    createChannels(context)
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val pending = PendingIntent.getActivity(context, id, Intent(Intent.ACTION_VIEW, Uri.parse(url)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val cleanBody = body.replace(Regex("\\s+"), " ").take(700).ifBlank { "Toque para abrir a fonte." }
    val notification = NotificationCompat.Builder(context, channel)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title.take(120))
        .setContentText(cleanBody.take(180))
        .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()
    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
}
