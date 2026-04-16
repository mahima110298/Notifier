package com.notifier.whatsapp

import android.content.Context
import android.util.Log

object MessageMatcher {
    private const val TAG = "MessageMatcher"

    data class MatchResult(
        val matched: Boolean,
        val reason: String = ""
    )

    fun matchesGroup(context: Context, message: WhatsAppMessage): Boolean =
        matchesGroup(AppConfig.getTargetGroup(context), message)

    fun matchesSender(context: Context, message: WhatsAppMessage): Boolean =
        matchesSender(AppConfig.getAllowedSenders(context), message)

    /**
     * Pure-function overload for testing / callers that already have the config.
     * An empty/blank targetGroup means "match any group".
     */
    fun matchesGroup(targetGroup: String, message: WhatsAppMessage): Boolean {
        if (targetGroup.isBlank()) return true

        val groupName = WhatsAppNotificationParser.getGroupName(message)
        val matches = groupName.equals(targetGroup, ignoreCase = true)

        Log.d(TAG, "Group match: '$groupName' vs '$targetGroup' = $matches")
        return matches
    }

    /**
     * Pure-function overload for testing / callers that already have the config.
     * An empty allowedSenders list means "match any sender".
     */
    fun matchesSender(allowedSenders: List<String>, message: WhatsAppMessage): Boolean {
        if (allowedSenders.isEmpty()) return true

        val matches = allowedSenders.any { allowed ->
            message.sender.equals(allowed, ignoreCase = true) ||
                    message.sender.contains(allowed, ignoreCase = true)
        }

        Log.d(TAG, "Sender match: '${message.sender}' in $allowedSenders = $matches")
        return matches
    }
}
