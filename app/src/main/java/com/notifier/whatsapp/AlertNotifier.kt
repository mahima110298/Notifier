package com.notifier.whatsapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

object AlertNotifier {
    const val CHANNEL_ID = "whatsapp_match_alerts"
    private val notificationId = AtomicInteger(1000)
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            enableVibration(true)
            enableLights(true)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    fun sendAlert(
        context: Context,
        message: WhatsAppMessage,
        matchReason: String
    ) {
        val timeStr = timeFormat.format(Date(message.timestamp))
        val groupName = WhatsAppNotificationParser.getGroupName(message)

        val title = "Match found in $groupName"
        val body = "${message.sender} at $timeStr:\n${message.messageBody}"

        val bigTextStyle = NotificationCompat.BigTextStyle()
            .bigText(body)
            .setSummaryText("Reason: $matchReason")

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("${message.sender}: ${message.messageBody}")
            .setStyle(bigTextStyle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(notificationId.getAndIncrement(), notification)

        // Store match for UI display
        MatchHistory.addMatch(context, message, matchReason)
    }
}
