package __APP_ID__.core.download

import __APP_ID__.DarkTubeApplication
import __APP_ID__.MainActivity
import __APP_ID__.core.log.AppLog
import __APP_ID__.data.db.DownloadEntity
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Notifications for downloads. Technical text never appears here. */
interface DownloadNotifier {
    fun progress(count: Int, title: String?, progress: Float)
    fun completed(e: DownloadEntity)
    fun failed(e: DownloadEntity)
}

class AndroidDownloadNotifier(private val context: Context) : DownloadNotifier {
    private var lastProgress = 0L

    override fun progress(count: Int, title: String?, progress: Float) {
        // The foreground service owns the ongoing notification and re-posts it from ActiveInfo; nothing to do.
    }

    override fun completed(e: DownloadEntity) {
        post(e.id.toInt() + DONE_BASE, "Download complete", e.destinationName ?: e.title)
    }

    override fun failed(e: DownloadEntity) {
        post(e.id.toInt() + DONE_BASE, "Download failed", "${e.title}: ${e.errorMessage ?: ""}".trim())
    }

    private fun post(id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    companion object {
        const val CHANNEL_ID = "darktube_downloads"
        const val ONGOING_ID = 4201
        private const val DONE_BASE = 5000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Progress of downloads in DarkTube"
                        setShowBadge(false)
                    },
                )
            }
        }

        fun openApp(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * Foreground service (type dataSync) that keeps the process alive while downloads run, so they
 * continue when the app is minimized or the screen is off. It shows "DarkTube / Downloading: <title>"
 * with a progress bar and stops itself when the queue is empty. START_STICKY: if Android kills the
 * process, the service is restarted and the manager resumes the interrupted jobs from their partial files.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AndroidDownloadNotifier.ensureChannel(this)
        val manager = (application as DarkTubeApplication).container.downloads
        startInForeground(build(manager.active.value))
        acquireWakeLock()
        manager.start()
        if (watcher == null) {
            watcher = scope.launch {
                manager.active.collectLatest { info ->
                    if (info.count == 0) {
                        // Debounced: right after a (re)start the queue may not have been scheduled yet.
                        delay(STOP_GRACE_MS)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        runCatching { NotificationManagerCompat.from(this@DownloadService).notify(AndroidDownloadNotifier.ONGOING_ID, build(info)) }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, AndroidDownloadNotifier.ONGOING_ID, n, type)
    }

    private fun build(info: ActiveInfo): Notification {
        val text = when {
            info.count == 0 -> "Starting…"
            info.count == 1 -> "Downloading: ${info.title ?: ""}"
            else -> "Downloading ${info.count} items: ${info.title ?: ""}"
        }
        val b = NotificationCompat.Builder(this, AndroidDownloadNotifier.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DarkTube")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(AndroidDownloadNotifier.openApp(this))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (info.indeterminate) b.setProgress(0, 0, true) else b.setProgress(100, (info.progress * 100).toInt().coerceIn(0, 100), false)
        return b.build()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DarkTube:downloads").apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MAX_MS)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        private const val STOP_GRACE_MS = 4_000L
        private const val WAKE_LOCK_MAX_MS = 6L * 60 * 60 * 1000

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Android 12+ can refuse a foreground start from the background; the queue is persisted
                // and resumes the next time the app is opened.
                AppLog.w("Download", "could not start the download service: ${e.javaClass.simpleName}")
            }
        }
    }
}
