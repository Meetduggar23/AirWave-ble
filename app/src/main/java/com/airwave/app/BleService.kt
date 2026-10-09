package com.airwave.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BleService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_running))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, n)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "airwave_service"
        private const val NOTIF_ID = 2001

        fun start(ctx: Context) {
            val i = Intent(ctx.applicationContext, BleService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.applicationContext.startForegroundService(i)
                else ctx.applicationContext.startService(i)
            } catch (_: Exception) { }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(
                    Intent(ctx.applicationContext, BleService::class.java))
            } catch (_: Exception) { }
        }
    }
}
