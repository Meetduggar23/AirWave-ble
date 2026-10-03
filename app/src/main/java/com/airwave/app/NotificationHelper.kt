package com.airwave.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object NotificationHelper {
    private const val CHANNEL_ID = "airwave_messages"

    /** v3.2.5 extras so tapping a message notification opens the right chat. */
    const val EXTRA_CONV_ID = "airwave_conv_id"
    const val EXTRA_IS_GROUP = "airwave_is_group"

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "AirWave messages",
                        NotificationManager.IMPORTANCE_HIGH
                    )
                )
            }
        }
    }

    fun show(ctx: Context, sender: String, text: String) {
        showCustom(ctx, sender, text, MainActivity::class.java, sender.hashCode())
    }

    /** v3.2.5: message notification carrying its conversation for deep-linking. */
    fun show(ctx: Context, sender: String, text: String, convId: String, isGroup: Boolean) {
        ensureChannel(ctx)
        val intent = Intent(ctx, MainActivity::class.java).apply {
            putExtra(EXTRA_CONV_ID, convId)
            putExtra(EXTRA_IS_GROUP, isGroup)
        }
        val pi = PendingIntent.getActivity(
            ctx, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(sender)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(convId.hashCode(), notification)
    }

    /** v3.0: notification variant with a custom target activity and id. */
    fun showCustom(ctx: Context, title: String, text: String, target: Class<*>, id: Int) {
        ensureChannel(ctx)
        val intent = Intent(ctx, target)
        val pi = PendingIntent.getActivity(
            ctx, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(id, notification)
    }
}
