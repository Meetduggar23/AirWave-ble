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

    /**
     * v3.2.5: chat message notification. Tapping it opens the actual chat
     * (DM or group) instead of the home screen.
     */
    fun showChat(ctx: Context, convId: String, sender: String, text: String) {
        val isGroup = convId.startsWith("group:")
        val intent = Intent(
            ctx,
            if (isGroup) GroupChatActivity::class.java else ChatActivity::class.java
        ).apply {
            if (isGroup) {
                putExtra("group_id", convId.removePrefix("group:"))
            } else {
                // v4.0: convId carries a contact id, not a BLE address.
                val contactId = convId.removePrefix("${AirWaveBle.DM}:")
                putExtra("contact_id", contactId)
                AirWaveBle.bleAddressForContact(contactId)?.let {
                    putExtra("peer_address", it)
                }
                putExtra("peer_name", sender)
            }
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            ctx, convId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        ensureChannel(ctx)
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
