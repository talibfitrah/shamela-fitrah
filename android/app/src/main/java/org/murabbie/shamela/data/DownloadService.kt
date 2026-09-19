package org.murabbie.shamela.data

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.murabbie.shamela.App
import org.murabbie.shamela.R
import org.murabbie.shamela.ui.MainActivity

/** خدمة أمامية تُبقي تنزيل الكتب حيًّا ولو غادر المستخدم التطبيق، مع إشعار تقدّم. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tracker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat(build("تنزيل الكتب…", -1f))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAUSE) {
            App.instance.downloads.pauseAll()
            finish(); return START_NOT_STICKY
        }
        if (tracker?.isActive != true) tracker = scope.launch { track() }
        return START_NOT_STICKY
    }

    private suspend fun track() {
        val dl = App.instance.downloads
        var last = ""
        var idle = 0
        while (true) {
            if (!dl.isActive()) { if (++idle > 3) break } else idle = 0
            val (text, p) = dl.summary()
            if (text != last) {
                last = text
                runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, build(text, p)) }
            }
            delay(1000)
        }
        val done = dl.jobs.value.values.count { it.status == JobStatus.DONE }
        val err = dl.jobs.value.values.count { it.status == JobStatus.ERROR }
        if (done > 0 || err > 0) notifyDone(done, err)
        finish()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    private fun build(text: String, progress: Float): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java).putExtra("open", "downloads"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 2, Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("المكتبة الشاملة — التنزيلات")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "إيقاف مؤقت", pause)
        if (progress >= 0f) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
        return b.build()
    }

    private fun notifyDone(done: Int, err: Int) {
        val open = PendingIntent.getActivity(this, 3, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = buildString {
            if (done > 0) append("اكتمل تنزيل ").append(ArabicText.arabicDigits(done)).append(if (done == 1) " كتاب" else " كتب")
            if (err > 0) { if (isNotEmpty()) append("؛ "); append("تعذر ").append(ArabicText.arabicDigits(err)) }
        }
        val n = NotificationCompat.Builder(this, App.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("المكتبة الشاملة")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_DONE_ID, n) }
    }

    private fun finish() {
        tracker?.cancel()
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val NOTIF_ID = 2001
        const val NOTIF_DONE_ID = 2002
        const val ACTION_PAUSE = "org.murabbie.shamela.DL_PAUSE"
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            runCatching { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent) }
        }
    }
}
