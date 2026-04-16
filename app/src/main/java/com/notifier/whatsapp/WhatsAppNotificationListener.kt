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
        // Never process our own alert notifications — otherwise DEBUG_ACCEPT_ALL_PACKAGES
        // could loop them back through the pipeline.
        if (sbn.packageName == packageName) return

        // Debug builds: record every incoming notification (pre-filter) so the
        // UI's "Captured Notifications" card can show what the listener sees.
        CapturedNotifications.record(applicationContext, sbn)

        if (!WhatsAppNotificationParser.isWhatsApp(sbn)) {
            if (!BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) return
            Log.d(TAG, "DEBUG_ACCEPT_ALL_PACKAGES: accepting notification from ${sbn.packageName}")
        }

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

        // Route on group vs 1:1.
        //   Group messages:  must pass TARGET_GROUPS + ALLOWED_SENDERS filters.
        //   1:1 messages:    must pass TARGET_INDIVIDUALS filter (opt-in).
        if (message.isGroupMessage) {
            if (!MessageMatcher.matchesGroup(applicationContext, message)) {
                Log.d(TAG, "Group mismatch, skipping")
                return
            }
            if (!MessageMatcher.matchesSender(applicationContext, message)) {
                Log.d(TAG, "Sender mismatch, skipping")
                return
            }
        } else {
            if (!MessageMatcher.matchesIndividual(applicationContext, message)) {
                Log.d(TAG, "Individual mismatch, skipping")
                return
            }
        }

        // Record the pre-LLM candidate before the LLM runs. If the LLM call
        // never finishes (network error, hang), the entry stays "pending",
        // which is itself diagnostic signal.
        PreLlmMatches.addPending(applicationContext, message)

        // Run LLM matching asynchronously
        scope.launch {
            try {
                val llmResult = LlmMatcher.matchMessage(applicationContext, message)
                Log.d(TAG, "LLM result: matches=${llmResult.matches}, reason=${llmResult.reason}")

                val status = when {
                    llmResult.reason.contains("not configured", ignoreCase = true) -> "skipped"
                    llmResult.reason.startsWith("Error:", ignoreCase = true) ||
                        llmResult.reason.contains("API error", ignoreCase = true) -> "error"
                    llmResult.matches -> "yes"
                    else -> "no"
                }
                PreLlmMatches.updateLlmResult(applicationContext, message, status, llmResult.reason)

                if (llmResult.matches) {
                    AlertNotifier.sendAlert(applicationContext, message, llmResult.reason)
                    Log.i(TAG, "ALERT sent for message from ${message.sender}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during LLM matching", e)
                PreLlmMatches.updateLlmResult(
                    applicationContext, message, "error", e.message ?: e.javaClass.simpleName
                )
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
