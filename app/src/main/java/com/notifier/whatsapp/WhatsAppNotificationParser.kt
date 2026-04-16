package com.notifier.whatsapp

import android.app.Notification
import android.os.Bundle
import android.service.notification.StatusBarNotification

data class WhatsAppMessage(
    val title: String,        // EXTRA_TITLE — group name or contact
    val text: String,         // EXTRA_TEXT — latest message preview
    val bigText: String,      // EXTRA_BIG_TEXT — expanded message
    val subText: String,      // EXTRA_SUB_TEXT — often the group name
    val sender: String,       // Extracted sender name
    val messageBody: String,  // Extracted message content
    val messages: List<String>, // EXTRA_MESSAGES — message history lines
    val timestamp: Long,      // When the notification was posted
    val isGroupMessage: Boolean
)

object WhatsAppNotificationParser {
    private const val WHATSAPP_PACKAGE = "com.whatsapp"

    fun isWhatsApp(sbn: StatusBarNotification): Boolean =
        sbn.packageName == WHATSAPP_PACKAGE

    fun parse(sbn: StatusBarNotification): WhatsAppMessage? {
        if (!isWhatsApp(sbn)) return null

        val extras: Bundle = sbn.notification.extras ?: return null

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

        // Extract messages from EXTRA_MESSAGES (Messaging style notifications)
        val messages = mutableListOf<String>()
        val extraMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (extraMessages != null) {
            for (msg in extraMessages) {
                if (msg is Bundle) {
                    val msgText = msg.getCharSequence("text")?.toString() ?: ""
                    val msgSender = msg.getCharSequence("sender")?.toString() ?: ""
                    if (msgText.isNotBlank()) {
                        messages.add(if (msgSender.isNotBlank()) "$msgSender: $msgText" else msgText)
                    }
                }
            }
        }

        // Determine if this is a group message
        // Group messages typically have format "Sender @ Group" or "Group: Sender: Message"
        val isGroup = title.contains(" @ ") ||
                subText.isNotBlank() ||
                text.contains(": ") // "Sender: message" format in group chats

        // Extract sender and message body
        val (sender, messageBody) = extractSenderAndMessage(title, text, bigText, isGroup)

        return WhatsAppMessage(
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            sender = sender,
            messageBody = messageBody,
            messages = messages,
            timestamp = sbn.postTime,
            isGroupMessage = isGroup
        )
    }

    private fun extractSenderAndMessage(
        title: String,
        text: String,
        bigText: String,
        isGroup: Boolean
    ): Pair<String, String> {
        if (!isGroup) {
            // Direct message: title is sender, text is message
            return Pair(title, bigText.ifBlank { text })
        }

        // Group message patterns:
        // Title: "Group Name" , Text: "Sender: message"
        // Title: "Sender @ Group" , Text: "message"
        if (title.contains(" @ ")) {
            val sender = title.substringBefore(" @ ").trim()
            return Pair(sender, bigText.ifBlank { text })
        }

        // Most common: title is group name, text is "Sender: message"
        if (text.contains(": ")) {
            val sender = text.substringBefore(": ").trim()
            val message = text.substringAfter(": ").trim()
            val fullMessage = bigText.ifBlank { message }
            return Pair(sender, fullMessage)
        }

        return Pair("Unknown", bigText.ifBlank { text })
    }

    fun getGroupName(msg: WhatsAppMessage): String {
        // subText is the most reliable group name indicator
        if (msg.subText.isNotBlank()) return msg.subText

        // "Sender @ Group" format
        if (msg.title.contains(" @ ")) return msg.title.substringAfter(" @ ").trim()

        // If it's a group message, title is often the group name
        if (msg.isGroupMessage && !msg.title.contains(": ")) return msg.title

        return msg.title
    }
}
