package com.menezes.concursoswatch

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
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
private const val ALERT_CHANNEL = "concursos_alertas_importantes"
private const val NEW_CHANNEL = "concursos_novos"
private const val UNIQUE_WORK = "concursos_watch_periodic_v2"
private const val REPO = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos"
private const val REPO_URL = "https://github.com/$REPO"
private const val ALERTS_API = "https://api.github.com/repos/$REPO/issues?state=open&labels=watch-alert&per_page=60&sort=created&direction=desc"
private const val NEW_CONTESTS_URL = "https://raw.githubusercontent.com/$REPO/main/state/new_contests.json"

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var latest: TextView
    private lateinit var contestsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannels(this)
        requestNotificationPermissionIfNeeded()
        buildUi()
        scheduleMonitor()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
        }

        root.addView(TextView(this).apply {
            text = "Concursos Watch"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Só o que merece sua atenção"
            textSize = 15f
            setPadding(0, dp(4), 0, dp(18))
        })

        status = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(20))
        }
        root.addView(status)

        root.addView(sectionTitle("Alertas importantes"))
        root.addView(TextView(this).apply {
            text = "Convocação, nomeação, posse, classificação/homologação relevante, reclassificação e eventos prioritários. Ruído técnico fica fora daqui."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })
        latest = TextView(this).apply {
            textSize = 15f
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setBackgroundColor(0xFFF3F4F6.toInt())
        }
        root.addView(latest)
        root.addView(space(22))

        root.addView(sectionTitle("Concursos novos"))
        root.addView(TextView(this).apply {
            text = "Editais e processos seletivos recém-detectados em fontes oficiais. Resultados, gabaritos e convocações não entram nesta lista."
            textSize = 13f
            setPadding(0, dp(4), 0, dp(10))
        })
        contestsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(contestsContainer)
        root.addView(space(18))

        val checkNow = Button(this).apply {
            text = "Verificar agora"
            setOnClickListener {
                WorkManager.getInstance(this@MainActivity)
                    .enqueue(OneTimeWorkRequestBuilder<AlertWorker>().build())
                text = "Verificando…"
                isEnabled = false
                Handler(Looper.getMainLooper()).postDelayed({
                    text = "Verificar agora"
                    isEnabled = true
                    refreshUi()
                }, 4500)
            }
        }
        root.addView(checkNow)
        root.addView(space(8))
        root.addView(Button(this).apply {
            text = "Abrir monitor no GitHub"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
        })
        root.addView(TextView(this).apply {
            text = "O app sincroniza em segundo plano em intervalos de aproximadamente 15 minutos. O Android pode atrasar execuções para economizar bateria."
            textSize = 12f
            setPadding(0, dp(18), 0, 0)
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun refreshUi() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sync = prefs.getLong(KEY_LAST_SYNC, 0L)
        status.text = if (sync == 0L) {
            "Aguardando a primeira sincronização"
        } else {
            val whenText = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(sync))
            "Conectado • última sincronização $whenText"
        }

        val title = prefs.getString(KEY_LAST_TITLE, null)
        val url = prefs.getString(KEY_LAST_URL, null)
        latest.text = if (title.isNullOrBlank()) {
            "Nenhum alerta importante novo neste aparelho."
        } else {
            "Último alerta\n\n$title\n\n${url.orEmpty()}"
        }

        renderNewContests(prefs.getString(KEY_NEW_CONTESTS_CACHE, null))
    }

    private fun renderNewContests(raw: String?) {
        contestsContainer.removeAllViews()
        if (raw.isNullOrBlank()) {
            contestsContainer.addView(TextView(this).apply {
                text = "Feed ainda não sincronizado."
                textSize = 14f
            })
            return
        }
        try {
            val items = JSONObject(raw).optJSONArray("items") ?: JSONArray()
            if (items.length() == 0) {
                contestsContainer.addView(TextView(this).apply {
                    text = "Nenhum concurso novo detectado nas fontes monitoradas."
                    textSize = 14f
                })
                return
            }
            for (i in 0 until minOf(items.length(), 12)) {
                val item = items.getJSONObject(i)
                val title = item.optString("title", "Novo concurso/processo seletivo")
                val city = item.optString("city", "")
                val source = item.optString("source", "")
                val url = item.optString("url", REPO_URL)
                contestsContainer.addView(Button(this).apply {
                    text = buildString {
                        append(title)
                        if (city.isNotBlank()) append("\n$city")
                        if (source.isNotBlank()) append(" • $source")
                    }
                    isAllCaps = false
                    setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                })
                contestsContainer.addView(space(7))
            }
        } catch (_: Exception) {
            contestsContainer.addView(TextView(this).apply {
                text = "Não foi possível ler o feed de concursos novos. Tente 'Verificar agora'."
                textSize = 14f
            })
        }
    }

    private fun scheduleMonitor() {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE, request
        )
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<AlertWorker>().build())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun space(height: Int = 14): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }
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
        val useful = fetchIssues().filter(::isUsefulAlert)
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
        prefs.edit().putString(KEY_NEW_CONTESTS_CACHE, raw).apply()

        val currentUrls = mutableSetOf<String>()
        val byUrl = mutableMapOf<String, JSONObject>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val url = item.optString("url", "")
            if (url.isNotBlank()) {
                currentUrls += url
                byUrl[url] = item
            }
        }

        val baselineDone = prefs.getBoolean(KEY_CONTESTS_BASELINED, false)
        val known = prefs.getStringSet(KEY_KNOWN_CONTEST_URLS, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (!baselineDone) {
            prefs.edit()
                .putStringSet(KEY_KNOWN_CONTEST_URLS, currentUrls)
                .putBoolean(KEY_CONTESTS_BASELINED, true)
                .apply()
            return
        }

        val newUrls = currentUrls - known
        newUrls.take(8).forEachIndexed { index, url ->
            val item = byUrl[url] ?: return@forEachIndexed
            val title = item.optString("title", "Novo concurso/processo seletivo")
            val city = item.optString("city", "")
            val source = item.optString("source", "")
            val body = listOf(city, source).filter { it.isNotBlank() }.joinToString(" • ")
            notifyItem(applicationContext, NEW_CHANNEL, 900000 + index + url.hashCode().ushr(1), title, body, url)
        }
        known += currentUrls
        prefs.edit().putStringSet(KEY_KNOWN_CONTEST_URLS, known).apply()
    }

    private fun fetchIssues(): List<AlertIssue> {
        val body = fetchText(ALERTS_API)
        val json = JSONArray(body)
        val result = mutableListOf<AlertIssue>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.has("pull_request")) continue
            result += AlertIssue(
                number = item.getInt("number"),
                title = item.optString("title", "Novo alerta"),
                body = item.optString("body", ""),
                url = item.optString("html_url", REPO_URL)
            )
        }
        return result
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/json, text/plain")
        connection.setRequestProperty("User-Agent", "ConcursosWatch-Android/1.1")
        val code = connection.responseCode
        if (code !in 200..299) error("HTTP $code")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()
        return body
    }
}

data class AlertIssue(val number: Int, val title: String, val body: String, val url: String)

private fun normalized(value: String): String {
    val n = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
    return n.lowercase()
}

private fun isUsefulAlert(issue: AlertIssue): Boolean {
    val text = normalized(issue.title + " " + issue.body)
    if ("prioridade" in text) return true
    if ("documento alterado" in text || "deep audit indisponivel" in text || "fonte indisponivel" in text) return false
    val useful = listOf(
        "convoca", "nomea", "posse", "classificacao final", "homolog",
        "reclassifica", "resultado final", "atribuicao", "exame admissional",
        "entrega de documentos", "desistencia"
    )
    return useful.any { it in text }
}

private fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            ALERT_CHANNEL, "Alertas importantes", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Convocações, nomeações, homologações e mudanças prioritárias"
            enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel(
            NEW_CHANNEL, "Concursos novos", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Novos editais e processos seletivos detectados em fontes oficiais"
            enableVibration(true)
        })
    }
}

private fun notifyItem(context: Context, channel: String, id: Int, title: String, body: String, url: String) {
    createChannels(context)
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val pending = PendingIntent.getActivity(
        context, id,
        Intent(Intent.ACTION_VIEW, Uri.parse(url)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
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
