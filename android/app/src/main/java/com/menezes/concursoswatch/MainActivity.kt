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
import android.view.Gravity
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
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val PREFS = "concursos_watch"
private const val KEY_LAST_SEEN = "last_seen_issue"
private const val KEY_LAST_SYNC = "last_sync"
private const val KEY_LAST_TITLE = "last_title"
private const val KEY_LAST_URL = "last_url"
private const val CHANNEL_ID = "concursos_alertas"
private const val UNIQUE_WORK = "concursos_watch_periodic"
private const val REPO = "menezesx2k26-byte/Verifica-ao-Diaria-dos-Concursos"
private const val REPO_URL = "https://github.com/$REPO"
private const val API_URL = "https://api.github.com/repos/$REPO/issues?state=open&labels=watch-alert&per_page=30&sort=created&direction=desc"

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var latest: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel(this)
        requestNotificationPermissionIfNeeded()
        scheduleMonitor()
        buildUi()
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(30))
        }

        val title = TextView(this).apply {
            text = "Concursos Watch"
            textSize = 28f
            setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = TextView(this).apply {
            text = "Alertas do monitor conectado ao GitHub"
            textSize = 16f
            setPadding(0, dp(6), 0, dp(24))
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, dp(18))
        }

        latest = TextView(this).apply {
            textSize = 15f
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xFFF3F4F6.toInt())
        }

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
                }, 3500)
            }
        }

        val openRepo = Button(this).apply {
            text = "Abrir monitor no GitHub"
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
            }
        }

        val note = TextView(this).apply {
            text = "O Android consulta o feed de alertas a cada ~15 minutos. O GitHub Actions também verifica as fontes a cada ~15 minutos. Alertas novos aparecem como notificação do sistema."
            textSize = 13f
            setPadding(0, dp(22), 0, 0)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)
        root.addView(latest)
        root.addView(space())
        root.addView(checkNow)
        root.addView(space(8))
        root.addView(openRepo)
        root.addView(note)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun refreshUi() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sync = prefs.getLong(KEY_LAST_SYNC, 0L)
        val title = prefs.getString(KEY_LAST_TITLE, null)
        val url = prefs.getString(KEY_LAST_URL, null)
        val lastSeen = prefs.getInt(KEY_LAST_SEEN, 0)

        status.text = if (sync == 0L) {
            "Status: aguardando a primeira sincronização."
        } else {
            val whenText = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(sync))
            "Status: conectado • última checagem $whenText • evento #$lastSeen"
        }

        latest.text = if (title.isNullOrBlank()) {
            "Nenhum alerta recebido neste aparelho ainda. Na primeira sincronização o app cria um baseline e passa a avisar apenas eventos novos."
        } else {
            "Último alerta\n\n$title\n\n${url.orEmpty()}"
        }
    }

    private fun scheduleMonitor() {
        val request = PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            UNIQUE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
        WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<AlertWorker>().build())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun space(height: Int = 14): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height)).also { it.gravity = Gravity.CENTER_HORIZONTAL }
    }
}

class AlertWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val issues = fetchIssues()
            val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getInt(KEY_LAST_SEEN, 0)
            val maxNumber = issues.maxOfOrNull { it.number } ?: previous

            if (previous == 0) {
                // Primeira instalação: não despeja o histórico inteiro no aparelho.
                prefs.edit()
                    .putInt(KEY_LAST_SEEN, maxNumber)
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()
                return@withContext Result.success()
            }

            issues.filter { it.number > previous }
                .sortedBy { it.number }
                .forEach { issue ->
                    notifyIssue(applicationContext, issue)
                    prefs.edit()
                        .putString(KEY_LAST_TITLE, issue.title)
                        .putString(KEY_LAST_URL, issue.url)
                        .apply()
                }

            prefs.edit()
                .putInt(KEY_LAST_SEEN, maxNumber)
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun fetchIssues(): List<AlertIssue> {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "ConcursosWatch-Android/1.0")

        val code = connection.responseCode
        if (code !in 200..299) error("GitHub HTTP $code")
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        connection.disconnect()

        val json = JSONArray(body)
        val result = mutableListOf<AlertIssue>()
        for (i in 0 until json.length()) {
            val item = json.getJSONObject(i)
            if (item.has("pull_request")) continue
            result += AlertIssue(
                number = item.getInt("number"),
                title = item.optString("title", "Novo alerta de concurso"),
                body = item.optString("body", ""),
                url = item.optString("html_url", REPO_URL)
            )
        }
        return result
    }
}

data class AlertIssue(val number: Int, val title: String, val body: String, val url: String)

private fun createChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Alertas de concursos",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Convocações, resultados, homologações e outras mudanças relevantes"
                enableVibration(true)
            }
        )
    }
}

private fun notifyIssue(context: Context, issue: AlertIssue) {
    createChannel(context)
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return

    val open = Intent(Intent.ACTION_VIEW, Uri.parse(issue.url))
    val pending = PendingIntent.getActivity(
        context,
        issue.number,
        open,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val cleanBody = issue.body
        .replace(Regex("\\s+"), " ")
        .take(700)
        .ifBlank { "Abra o alerta para conferir os detalhes." }

    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(issue.title.take(120))
        .setContentText(cleanBody.take(180))
        .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .setContentIntent(pending)
        .build()

    (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
        .notify(issue.number, notification)
}
