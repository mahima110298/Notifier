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

    // WhatsApp suffixes the title with "(N messages)" / "(N new messages)"
    // when bundling multiple pending messages into a single notification.
    // Strip it so the group-name equality check ("You n Me" == "You n Me")
    // isn't broken by "You n Me (2 messages)".
    private val COUNT_SUFFIX = Regex("""\s*\(\d+\s+(new\s+)?messages?\)\s*$""", RegexOption.IGNORE_CASE)

    internal fun stripCountSuffix(s: String): String = s.replace(COUNT_SUFFIX, "").trim()

    fun isWhatsApp(sbn: StatusBarNotification): Boolean =
        sbn.packageName == WHATSAPP_PACKAGE

    fun parse(sbn: StatusBarNotification): WhatsAppMessage? {
        // Package filtering happens in the listener (so it can honor
        // BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES). Here we just parse.
        val extras: Bundle = sbn.notification.extras ?: return null

        val rawTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val title = stripCountSuffix(rawTitle)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        // EXTRA_CONVERSATION_TITLE is set by MessagingStyle and is the most
        // reliable source for the group/conversation name when present.
        val conversationTitle = extras
            .getCharSequence("android.conversationTitle")?.toString()?.let(::stripCountSuffix)
            ?: ""
        // isGroupConversation is a boolean WhatsApp sets on MessagingStyle
        // notifications to flag groups vs 1:1 chats.
        val isGroupExtra = extras.getBoolean("android.isGroupConversation", false)

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

        // Determine if this is a group message.
        // Prefer the explicit MessagingStyle flag; fall back to heuristics.
        val isGroup = isGroupExtra ||
                conversationTitle.isNotBlank() && conversationTitle != title ||
                title.contains(" @ ") ||
                subText.isNotBlank() ||
                text.contains(": ") // "Sender: message" format in group chats

        // Extract sender and message body
        val (sender, messageBody) = extractSenderAndMessage(title, text, bigText, isGroup)

        return WhatsAppMessage(
            title = title,
            text = text,
            bigText = bigText,
            subText = subText.ifBlank { conversationTitle },
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
        // subText (or conversationTitle folded into it) is the most reliable
        // group-name indicator. Strip any "(N messages)" suffix before
        // comparing.
        if (msg.subText.isNotBlank()) return stripCountSuffix(msg.subText)

        // "Sender @ Group" format
        if (msg.title.contains(" @ ")) {
            return stripCountSuffix(msg.title.substringAfter(" @ ").trim())
        }

        // If it's a group message, title is often the group name
        if (msg.isGroupMessage && !msg.title.contains(": ")) {
            return stripCountSuffix(msg.title)
        }

        return stripCountSuffix(msg.title)
    }
}
