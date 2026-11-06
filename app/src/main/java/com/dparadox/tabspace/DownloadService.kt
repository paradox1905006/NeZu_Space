package com.dparadox.tabspace

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Keeps the process alive and shows one notification while downloads are running. */
class DownloadService : Service() {
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val active = DownloadEngine.activeRecords()
            if (active.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(ID, build(active))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = build(DownloadEngine.activeRecords())
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(ID, n)
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun build(active: List<DownloadRecord>): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = when (active.size) {
            0 -> "Downloads"
            1 -> active[0].fileName
            else -> "${active.size} downloads"
        }
        val total = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val done = active.sumOf { it.downloadedBytes }
        val known = active.isNotEmpty() && active.all { it.totalBytes > 0 }
        val b = Notification.Builder(this, DownloadEngine.CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (known && total > 0) {
            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
            b.setContentText("$pct%").setProgress(100, pct, false)
        } else b.setContentText("Downloading").setProgress(0, 0, true)
        return b.build()
    }

    companion object { private const val ID = 4711 }
}
