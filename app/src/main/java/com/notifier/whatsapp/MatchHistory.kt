package com.notifier.whatsapp

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object MatchHistory {
    private const val PREFS_NAME = "match_history"
    private const val KEY_HISTORY = "history"
    private const val MAX_ENTRIES = 20
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    fun addMatch(context: Context, message: WhatsAppMessage, reason: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_HISTORY, "") ?: ""
        val timeStr = timeFormat.format(Date(message.timestamp))
        val group = WhatsAppNotificationParser.getGroupName(message)

        val entry = "[$timeStr] $group — ${message.sender}: ${message.messageBody} (Reason: $reason)"

        val lines = existing.lines().filter { it.isNotBlank() }.toMutableList()
        lines.add(0, entry)
        if (lines.size > MAX_ENTRIES) {
            while (lines.size > MAX_ENTRIES) lines.removeAt(lines.lastIndex)
        }

        prefs.edit().putString(KEY_HISTORY, lines.joinToString("\n")).apply()
    }

    fun getHistory(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HISTORY, "") ?: ""
    }
}
