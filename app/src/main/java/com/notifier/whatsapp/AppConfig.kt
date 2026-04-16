package com.notifier.whatsapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app configuration backed by SharedPreferences.
 * Defaults are loaded from BuildConfig (which reads .env at build time).
 * Users can override via the UI, which persists to SharedPreferences.
 *
 * Config schema:
 *   target_groups        — comma-separated group names; blank or "all" = any group
 *   target_individuals   — comma-separated 1:1 contact names; blank = none, "all" = any
 *   allowed_senders      — comma-separated sender names (within groups); blank/"all" = any
 *   match_prompts        — newline-separated LLM criteria; match ANY → alert
 *   llm_api_key / llm_api_base_url / llm_model — LLM endpoint config
 *
 * Legacy (singular) keys from pre-list versions are migrated on first read.
 */
object AppConfig {
    private const val PREFS_NAME = "whatsapp_notifier_config"

    // Current (list) keys
    private const val KEY_TARGET_GROUPS = "target_groups"
    private const val KEY_TARGET_INDIVIDUALS = "target_individuals"
    private const val KEY_ALLOWED_SENDERS = "allowed_senders"
    private const val KEY_MATCH_PROMPTS = "match_prompts"

    // LLM endpoint (unchanged)
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_API_BASE_URL = "llm_api_base_url"
    private const val KEY_MODEL = "llm_model"

    // Legacy (singular) keys — migrated to the list keys on first read.
    private const val LEGACY_KEY_TARGET_GROUP = "target_group"
    private const val LEGACY_KEY_MATCH_PROMPT = "match_prompt"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- parsed (list) getters ----------------

    /** List of target group names. Empty = match any group. */
    fun getTargetGroups(context: Context): List<String> =
        parseCsv(getTargetGroupsRaw(context))

    /**
     * List of 1:1 contact names to monitor. Empty = don't match any 1:1 chat.
     * Special value "all" = match any 1:1 contact (returns a single-element
     * sentinel list containing "*" — callers should use [individualsWildcard]).
     */
    fun getTargetIndividuals(context: Context): List<String> =
        parseCsvWithWildcard(getTargetIndividualsRaw(context))

    /** Returns true if the individuals config is the wildcard "all". */
    fun individualsWildcard(context: Context): Boolean {
        val raw = getTargetIndividualsRaw(context).trim()
        return raw.equals("all", ignoreCase = true)
    }

    /** Within-group sender allow-list. Empty = any sender. */
    fun getAllowedSenders(context: Context): List<String> =
        parseCsv(getAllowedSendersRaw(context))

    /** List of LLM match criteria. A message matches if ANY criterion matches. */
    fun getMatchPrompts(context: Context): List<String> {
        val raw = getMatchPromptsRaw(context)
        // Accept both newlines (UI) and | (.env single-line) as separators.
        return raw.split('\n', '|').map { it.trim() }.filter { it.isNotBlank() }
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, null) ?: BuildConfig.LLM_API_KEY

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, null) ?: BuildConfig.LLM_API_BASE_URL

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, null) ?: BuildConfig.LLM_MODEL

    // ---------------- raw (UI) getters ----------------
    // Used by MainActivity to re-render the user's original text into the
    // edit fields. These also perform legacy migration on first read.

    fun getTargetGroupsRaw(context: Context): String = migrateAndRead(
        context,
        newKey = KEY_TARGET_GROUPS,
        legacyKey = LEGACY_KEY_TARGET_GROUP,
        default = BuildConfig.TARGET_GROUPS
    )

    fun getTargetIndividualsRaw(context: Context): String =
        prefs(context).getString(KEY_TARGET_INDIVIDUALS, null)
            ?: BuildConfig.TARGET_INDIVIDUALS

    fun getAllowedSendersRaw(context: Context): String =
        prefs(context).getString(KEY_ALLOWED_SENDERS, null)
            ?: BuildConfig.ALLOWED_SENDERS

    fun getMatchPromptsRaw(context: Context): String = migrateAndRead(
        context,
        newKey = KEY_MATCH_PROMPTS,
        legacyKey = LEGACY_KEY_MATCH_PROMPT,
        default = BuildConfig.MATCH_PROMPTS
    )

    // ---------------- save ----------------

    fun save(
        context: Context,
        targetGroups: String,       // raw user-entered text (comma-separated)
        targetIndividuals: String,  // raw; comma-separated
        allowedSenders: String,     // raw; comma-separated
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        matchPrompts: String        // raw; newline-separated (UI) or |-separated (env)
    ) {
        prefs(context).edit()
            .putString(KEY_TARGET_GROUPS, targetGroups)
            .putString(KEY_TARGET_INDIVIDUALS, targetIndividuals)
            .putString(KEY_ALLOWED_SENDERS, allowedSenders)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_BASE_URL, apiBaseUrl)
            .putString(KEY_MODEL, model)
            .putString(KEY_MATCH_PROMPTS, matchPrompts)
            // Remove legacy keys — we've superseded them.
            .remove(LEGACY_KEY_TARGET_GROUP)
            .remove(LEGACY_KEY_MATCH_PROMPT)
            .apply()
    }

    // ---------------- helpers ----------------

    /** Parse comma-separated list. "all" or blank → empty list (= match any). */
    private fun parseCsv(raw: String): List<String> {
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * Like [parseCsv] but distinguishes "all" (→ empty list, semantics of
     * "match any") from blank (→ empty list, semantics of "match none" for
     * opt-in categories like individuals). Callers must use
     * [individualsWildcard] to tell them apart.
     */
    private fun parseCsvWithWildcard(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        if (raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * If [newKey] is present → return it. Otherwise, if [legacyKey] is
     * present → promote its value to [newKey] (one-time migration) and
     * return it. Otherwise return [default].
     */
    private fun migrateAndRead(
        context: Context,
        newKey: String,
        legacyKey: String,
        default: String
    ): String {
        val p = prefs(context)
        val current = p.getString(newKey, null)
        if (current != null) return current

        val legacy = p.getString(legacyKey, null)
        if (legacy != null) {
            p.edit().putString(newKey, legacy).remove(legacyKey).apply()
            return legacy
        }
        return default
    }
}
