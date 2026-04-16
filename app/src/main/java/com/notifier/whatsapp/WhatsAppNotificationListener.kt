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

        // Iterate every enabled preset. A message alerts if ANY enabled
        // preset's filters + LLM approve it. First match wins — we stop
        // evaluating further presets to save LLM calls.
        val enabledPresets = PresetStore.enabled(applicationContext)
        if (enabledPresets.isEmpty()) {
            Log.d(TAG, "No enabled presets, skipping")
            return
        }

        scope.launch {
            for (preset in enabledPresets) {
                val passes = if (message.isGroupMessage) {
                    val groupOk = MessageMatcher.matchesGroups(preset.targetGroupsList(), message)
                    val senderOk = MessageMatcher.matchesSender(preset.allowedSendersList(), message)
                    groupOk && senderOk
                } else {
                    MessageMatcher.matchesIndividual(
                        preset.targetIndividualsList(),
                        preset.individualsWildcard(),
                        message
                    )
                }
                if (!passes) {
                    Log.d(TAG, "Preset '${preset.name}' filters rejected — skipping")
                    continue
                }

                try {
                    val llmResult = LlmMatcher.matchMessage(
                        applicationContext, message, preset.matchPromptsList()
                    )
                    Log.d(TAG, "Preset '${preset.name}' LLM: matches=${llmResult.matches}, reason=${llmResult.reason}")

                    if (llmResult.matches) {
                        val reason = "[${preset.name}] ${llmResult.reason}"
                        AlertNotifier.sendAlert(applicationContext, message, reason)
                        Log.i(TAG, "ALERT sent via preset '${preset.name}' for ${message.sender}")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Preset '${preset.name}' LLM error", e)
                }
            }
            Log.d(TAG, "No enabled preset matched; no alert")
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
