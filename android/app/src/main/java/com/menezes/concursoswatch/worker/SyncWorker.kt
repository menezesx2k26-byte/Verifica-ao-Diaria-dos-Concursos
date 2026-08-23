package com.menezes.concursoswatch.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.menezes.concursoswatch.MainActivity
import com.menezes.concursoswatch.R
import com.menezes.concursoswatch.data.ContestRepository
import com.menezes.concursoswatch.data.SettingsStore
import com.menezes.concursoswatch.model.Contest
import com.menezes.concursoswatch.model.NotificationPolicy
import kotlinx.coroutines.flow.first

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val repo = ContestRepository(applicationContext)
        val settings = SettingsStore(applicationContext).flow.first()
        val result = repo.syncAll()

        val contests = repo.contests().associateBy { it.id }
        result.newContestIds.mapNotNull(contests::get)
            .filter { NotificationPolicy.shouldNotify(it, settings) }
            .take(6)
            .forEach(::notifyContest)

        val alerts = repo.alerts().associateBy { it.id }
        result.newAlertIds.mapNotNull(alerts::get).filter { it.priority >= 70 }.take(6).forEach { a ->
            notify("important", a.id, a.title, a.body, Uri.parse("concursoswatch://alerts/${a.id}"))
        }
        return if (result.contestsOk || result.alertsOk) Result.success() else Result.retry()
    }

    private fun notifyContest(c: Contest) {
        val meta = listOf(c.organization, c.city, c.endDate.takeIf { it.isNotBlank() }?.let { "até $it" })
            .filterNotNull().filter { it.isNotBlank() }.joinToString(" • ")
        notify("new_contests", c.id.hashCode(), c.title, meta, Uri.parse("concursoswatch://contest/${c.id}"))
    }

    private fun notify(channel: String, id: Int, title: String, body: String, target: Uri) {
        createChannels(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(Intent.ACTION_VIEW, target, applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pending = PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title.take(120))
            .setContentText(body.take(180))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(700)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }
}

fun createChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("important", "Acompanhamentos prioritários", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Convocações, nomeações, posse e mudanças realmente importantes"
            enableVibration(true)
        })
        manager.createNotificationChannel(NotificationChannel("new_contests", "Concursos novos", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Novos editais e processos seletivos relevantes"
        })
    }
}
