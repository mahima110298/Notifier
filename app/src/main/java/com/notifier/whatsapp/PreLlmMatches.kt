package com.notifier.whatsapp

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic log of every message that passes the group + sender filters,
 * annotated with the LLM's final verdict once it arrives.
 *
 * Recent Matches shows only what the LLM approved. This log shows *every*
 * pre-LLM candidate, so you can tell whether the LLM is the reason a
 * message isn't alerting.
 *
 * Status values:
 *   - "pending"  — group+sender passed, LLM not finished yet
 *   - "yes"      — LLM said matches=true  (an alert was sent, also in Recent Matches)
 *   - "no"       — LLM said matches=false (no alert)
 *   - "skipped"  — no LLM API key configured (treated as yes by the pipeline)
 *   - "error"    — LLM call threw
 */
object PreLlmMatches {
    private const val TAG = "PreLlmMatches"
    private const val PREFS_NAME = "pre_llm_matches"
    private const val KEY_LOG = "log_json"
    private const val MAX_ENTRIES = 30
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // Serialize writes across coroutines — SharedPreferences.apply() is async
    // so a concurrent addPending + updateLlmResult race is possible.
    private val lock = Any()

    /** Call right after group + sender filters pass, before the LLM runs. */
    fun addPending(context: Context, message: WhatsAppMessage) = synchronized(lock) {
        Log.d(TAG, "addPending ts=${message.timestamp} sender=${message.sender} body=${message.messageBody.take(40)}")
        val entry = JSONObject().apply {
            put("ts", message.timestamp)
            put("group", WhatsAppNotificationParser.getGroupName(message))
            put("sender", message.sender)
            put("body", message.messageBody)
            put("llm_status", "pending")
            put("llm_reason", "")
        }
        val arr = read(context)
        val combined = JSONArray().apply {
            put(entry)
            for (i in 0 until arr.length()) put(arr.getJSONObject(i))
        }
        val trimmed = JSONArray().apply {
            for (i in 0 until minOf(combined.length(), MAX_ENTRIES)) put(combined.getJSONObject(i))
        }
        write(context, trimmed)
        Log.d(TAG, "  stored; total entries=${trimmed.length()}")
    }

    /** Call once the LLM returns (or fails). Matches the pending entry by timestamp. */
    fun updateLlmResult(
        context: Context,
        message: WhatsAppMessage,
        status: String,
        reason: String
    ) = synchronized(lock) {
        Log.d(TAG, "updateLlmResult ts=${message.timestamp} status=$status")
        val arr = read(context)
        var updated = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("ts") == message.timestamp && o.optString("llm_status") == "pending") {
                o.put("llm_status", status)
                o.put("llm_reason", reason)
                updated = true
                break
            }
        }
        if (updated) {
            write(context, arr)
            Log.d(TAG, "  entry updated")
        } else {
            Log.w(TAG, "  no pending entry with ts=${message.timestamp} found (already updated?)")
        }
    }

    /** Total number of entries currently stored. */
    fun count(context: Context): Int = read(context).length()

    fun getLog(context: Context): String {
        val arr = read(context)
        if (arr.length() == 0) return ""
        return buildString {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val time = timeFormat.format(Date(o.optLong("ts")))
                val status = o.optString("llm_status")
                val reason = o.optString("llm_reason")
                append("[$time] LLM: ${status.uppercase()}")
                if (reason.isNotBlank()) append(" — ").append(reason)
                append('\n')
                append("  ").append(o.optString("group"))
                    .append(" — ").append(o.optString("sender"))
                    .append(": ").append(o.optString("body"))
                if (i < arr.length() - 1) append("\n\n")
            }
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_LOG).apply()
    }

    private fun read(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LOG, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun write(context: Context, arr: JSONArray) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOG, arr.toString()).apply()
    }
}
