package com.notifier.whatsapp

import android.content.Context
import android.util.Log

object MessageMatcher {
    private const val TAG = "MessageMatcher"

    // ---------------- Context-aware entry points (read config automatically) ----------------

    fun matchesGroup(context: Context, message: WhatsAppMessage): Boolean =
        matchesGroups(AppConfig.getTargetGroups(context), message)

    fun matchesSender(context: Context, message: WhatsAppMessage): Boolean =
        matchesSender(AppConfig.getAllowedSenders(context), message)

    /**
     * Should we alert for this 1:1 (non-group) message?
     *   - If TARGET_INDIVIDUALS is blank in config → false (opt-in behaviour)
     *   - If it's "all" → true
     *   - Otherwise → true iff the contact (title) is in the list
     */
    fun matchesIndividual(context: Context, message: WhatsAppMessage): Boolean {
        if (AppConfig.individualsWildcard(context)) return true
        val individuals = AppConfig.getTargetIndividuals(context)
        return matchesIndividual(individuals, wildcard = false, message = message)
    }

    // ---------------- Pure overloads (no Android dependency — unit-testable) ----------------

    /**
     * Empty [targetGroups] → match any group (wildcard). Matches are
     * case-insensitive against the message's extracted group name.
     */
    fun matchesGroups(targetGroups: List<String>, message: WhatsAppMessage): Boolean {
        if (targetGroups.isEmpty()) return true
        val groupName = WhatsAppNotificationParser.getGroupName(message)
        val matches = targetGroups.any { it.equals(groupName, ignoreCase = true) }
        Log.d(TAG, "Group match: '$groupName' in $targetGroups = $matches")
        return matches
    }

    /** Empty [allowedSenders] → match any sender. Substring match, case-insensitive. */
    fun matchesSender(allowedSenders: List<String>, message: WhatsAppMessage): Boolean {
        if (allowedSenders.isEmpty()) return true
        val matches = allowedSenders.any { allowed ->
            message.sender.equals(allowed, ignoreCase = true) ||
                    message.sender.contains(allowed, ignoreCase = true)
        }
        Log.d(TAG, "Sender match: '${message.sender}' in $allowedSenders = $matches")
        return matches
    }

    /**
     * 1:1 contact filter.
     *   - [wildcard] = true → always match (user explicitly set "all")
     *   - otherwise → match iff the contact title is in [targetIndividuals]
     *     (empty list & not wildcard → no match; opt-in)
     */
    fun matchesIndividual(
        targetIndividuals: List<String>,
        wildcard: Boolean,
        message: WhatsAppMessage
    ): Boolean {
        if (wildcard) return true
        if (targetIndividuals.isEmpty()) {
            Log.d(TAG, "Individual filter: list empty (opt-in) → reject")
            return false
        }
        // For 1:1, WhatsApp sets title == contact name. We also check sender
        // (== title for direct messages, per the parser).
        val contact = if (message.title.isNotBlank()) message.title else message.sender
        val matches = targetIndividuals.any { allowed ->
            contact.equals(allowed, ignoreCase = true) ||
                    contact.contains(allowed, ignoreCase = true)
        }
        Log.d(TAG, "Individual match: '$contact' in $targetIndividuals = $matches")
        return matches
    }

    // ---------------- Deprecated single-group overload ----------------
    // Kept so existing tests that reference matchesGroup(String, ...) keep compiling.

    /** Single-target convenience — blank target = match any group. */
    fun matchesGroup(targetGroup: String, message: WhatsAppMessage): Boolean {
        val list = if (targetGroup.isBlank() || targetGroup.equals("all", ignoreCase = true)) {
            emptyList()
        } else {
            listOf(targetGroup)
        }
        return matchesGroups(list, message)
    }
}
