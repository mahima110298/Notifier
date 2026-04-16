package com.notifier.whatsapp

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class WhatsAppNotificationListener : NotificationListenerService() {
    private val TAG = "WANotifListener"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!WhatsAppNotificationParser.isWhatsApp(sbn)) return

        val message = WhatsAppNotificationParser.parse(sbn) ?: return

        Log.d(TAG, buildString {
            append("WhatsApp notification received:\n")
            append("  title=${message.title}\n")
            append("  text=${message.text}\n")
            append("  bigText=${message.bigText}\n")
            append("  subText=${message.subText}\n")
            append("  sender=${message.sender}\n")
            append("  messageBody=${message.messageBody}\n")
            append("  messages=${message.messages}\n")
            append("  isGroup=${message.isGroupMessage}\n")
            append("  group=${WhatsAppNotificationParser.getGroupName(message)}")
        })

        // Skip non-group messages if a target group is configured
        if (!message.isGroupMessage) {
            val targetGroup = AppConfig.getTargetGroup(applicationContext)
            if (targetGroup.isNotBlank()) {
                Log.d(TAG, "Skipping non-group message (target group is set)")
                return
            }
        }

        // Filter by group
        if (!MessageMatcher.matchesGroup(applicationContext, message)) {
            Log.d(TAG, "Group mismatch, skipping")
            return
        }

        // Filter by sender
        if (!MessageMatcher.matchesSender(applicationContext, message)) {
            Log.d(TAG, "Sender mismatch, skipping")
            return
        }

        // Run LLM matching asynchronously
        scope.launch {
            try {
                val llmResult = LlmMatcher.matchMessage(applicationContext, message)
                Log.d(TAG, "LLM result: matches=${llmResult.matches}, reason=${llmResult.reason}")

                if (llmResult.matches) {
                    AlertNotifier.sendAlert(applicationContext, message, llmResult.reason)
                    Log.i(TAG, "ALERT sent for message from ${message.sender}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during LLM matching", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // No action needed
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected")
        AlertNotifier.createNotificationChannel(applicationContext)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
