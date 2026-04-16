package com.notifier.whatsapp

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only log of *every* notification the listener sees, across all
 * packages and before any filtering. Backed by SharedPreferences; capped to
 * the last MAX_ENTRIES entries.
 *
 * Only written to when BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES is true — so
 * release APKs never accumulate these and the UI card stays hidden.
 */
object CapturedNotifications {
    private const val PREFS_NAME = "captured_notifications"
    private const val KEY_LOG = "log"
    private const val MAX_ENTRIES = 50
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun record(context: Context, sbn: StatusBarNotification) {
        if (!BuildConfig.DEBUG_ACCEPT_ALL_PACKAGES) return

        val extras = sbn.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        val entry = buildString {
            append('[').append(timeFormat.format(Date(sbn.postTime))).append("] ")
            append(sbn.packageName)
            if (title.isNotBlank()) append("  —  ").append(title)
            if (text.isNotBlank()) append(": ").append(text)
        }.trim()

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = (prefs.getString(KEY_LOG, "") ?: "")
            .lines()
            .filter { it.isNotBlank() }
            .toMutableList()
        lines.add(0, entry)
        while (lines.size > MAX_ENTRIES) lines.removeAt(lines.lastIndex)

        prefs.edit().putString(KEY_LOG, lines.joinToString("\n")).apply()
    }

    fun getLog(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOG, "") ?: ""

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOG).apply()
    }
}
