package com.aloevera.dao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

class DownloadService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            val active = Downloader.tasks.filter {
                it.state == State.QUEUED || it.state == State.RUNNING || it.state == State.SAVING
            }
            val nm = getSystemService(NotificationManager::class.java)
            if (active.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                val done = Downloader.tasks.count { it.state == State.DONE }
                if (done > 0) {
                    val n = Notification.Builder(this@DownloadService, CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_download_done)
                        .setContentTitle("DAO · downloads concluídos")
                        .setContentText("$done arquivo(s) salvo(s) na pasta DAO")
                        .setContentIntent(openIntent())
                        .setAutoCancel(true)
                        .build()
                    nm.notify(2, n)
                }
                stopSelf()
                return
            }
            nm.notify(1, buildNotification(active))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        val active = Downloader.tasks.filter {
            it.state == State.QUEUED || it.state == State.RUNNING || it.state == State.SAVING
        }
        startForeground(1, buildNotification(active), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun openIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, DownloadsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(active: List<DlTask>): Notification {
        var done = 0L
        var tot = 0L
        for (t in active) {
            if (t.total > 0) {
                done += t.downloaded()
                tot += t.total
            }
        }
        val pct = if (tot > 0) ((done * 100) / tot).toInt() else 0
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("DAO · baixando ${active.size} arquivo(s)")
            .setContentText("$pct%")
            .setOngoing(true)
            .setContentIntent(openIntent())
        if (tot > 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        return b.build()
    }

    companion object {
        const val CHANNEL = "dao"
    }
}
