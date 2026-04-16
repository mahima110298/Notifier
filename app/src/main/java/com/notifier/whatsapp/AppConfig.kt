package com.notifier.whatsapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Effective runtime configuration. Resolves the *active preset* (from
 * [PresetStore]) for matching fields, plus the global LLM endpoint from
 * SharedPreferences.
 *
 * Migration: if no presets exist on first call, one is synthesized from
 * the legacy per-field SharedPreferences keys (or BuildConfig defaults)
 * and marked active. Legacy keys are then removed.
 */
object AppConfig {
    private const val PREFS_NAME = "whatsapp_notifier_config"

    // Global LLM endpoint (unchanged, not per-preset).
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_API_BASE_URL = "llm_api_base_url"
    private const val KEY_MODEL = "llm_model"

    // Legacy per-field keys (migrated into a "Default" preset on first call).
    private const val LEGACY_KEY_TARGET_GROUPS = "target_groups"
    private const val LEGACY_KEY_TARGET_INDIVIDUALS = "target_individuals"
    private const val LEGACY_KEY_ALLOWED_SENDERS = "allowed_senders"
    private const val LEGACY_KEY_MATCH_PROMPTS = "match_prompts"
    private const val LEGACY_KEY_TARGET_GROUP = "target_group"     // very old singular
    private const val LEGACY_KEY_MATCH_PROMPT = "match_prompt"     // very old singular

    @Volatile private var migrated = false

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------- parsed (list) getters ----------------

    fun getTargetGroups(context: Context): List<String> =
        parseCsv(getTargetGroupsRaw(context))

    fun getTargetIndividuals(context: Context): List<String> =
        parseCsvWithWildcard(getTargetIndividualsRaw(context))

    fun individualsWildcard(context: Context): Boolean =
        getTargetIndividualsRaw(context).trim().equals("all", ignoreCase = true)

    fun getAllowedSenders(context: Context): List<String> =
        parseCsv(getAllowedSendersRaw(context))

    fun getMatchPrompts(context: Context): List<String> {
        val raw = getMatchPromptsRaw(context)
        return raw.split('\n', '|').map { it.trim() }.filter { it.isNotBlank() }
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, null) ?: BuildConfig.LLM_API_KEY

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, null) ?: BuildConfig.LLM_API_BASE_URL

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, null) ?: BuildConfig.LLM_MODEL

    // ---------------- raw (UI) getters ----------------

    fun getTargetGroupsRaw(context: Context): String {
        ensureDefaultPreset(context)
        return PresetStore.active(context)?.targetGroups ?: BuildConfig.TARGET_GROUPS
    }

    fun getTargetIndividualsRaw(context: Context): String {
        ensureDefaultPreset(context)
        return PresetStore.active(context)?.targetIndividuals ?: BuildConfig.TARGET_INDIVIDUALS
    }

    fun getAllowedSendersRaw(context: Context): String {
        ensureDefaultPreset(context)
        return PresetStore.active(context)?.allowedSenders ?: BuildConfig.ALLOWED_SENDERS
    }

    fun getMatchPromptsRaw(context: Context): String {
        ensureDefaultPreset(context)
        return PresetStore.active(context)?.matchPrompts ?: BuildConfig.MATCH_PROMPTS
    }

    // ---------------- save ----------------

    /**
     * Save the matching-form values (groups / individuals / senders /
     * prompts) into the currently-active preset. Creates a "Default" preset
     * if none exists. Does NOT touch the LLM endpoint settings.
     */
    fun saveMatchingConfig(
        context: Context,
        targetGroups: String,
        targetIndividuals: String,
        allowedSenders: String,
        matchPrompts: String
    ) {
        ensureDefaultPreset(context)
        val active = PresetStore.active(context) ?: ConfigPreset.newEmpty("Default")
        val updated = active.copy(
            targetGroups = targetGroups,
            targetIndividuals = targetIndividuals,
            allowedSenders = allowedSenders,
            matchPrompts = matchPrompts
        )
        PresetStore.save(context, updated)
        if (PresetStore.activeId(context) == null) PresetStore.setActive(context, updated.id)
    }

    /**
     * Save the global LLM endpoint config (api key / base url / model).
     * Only surfaced in debug builds via the LLM tab. Release builds fall
     * back to BuildConfig defaults baked from .env at build time.
     */
    fun saveLlmSettings(
        context: Context,
        apiKey: String,
        apiBaseUrl: String,
        model: String
    ) {
        prefs(context).edit()
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_BASE_URL, apiBaseUrl)
            .putString(KEY_MODEL, model)
            .apply()
    }

    // ---------------- helpers ----------------

    private fun parseCsv(raw: String): List<String> {
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun parseCsvWithWildcard(raw: String): List<String> {
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /**
     * On first call per-process: if PresetStore has no presets, synthesize
     * a "Default" preset from legacy SharedPreferences keys (or BuildConfig
     * defaults) and mark it active. Legacy keys are then removed.
     */
    private fun ensureDefaultPreset(context: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            migrated = true
        }
        if (PresetStore.list(context).isNotEmpty()) return

        val p = prefs(context)
        // Prefer the plural legacy keys; fall back to pre-plural singular, then BuildConfig.
        val groups = p.getString(LEGACY_KEY_TARGET_GROUPS, null)
            ?: p.getString(LEGACY_KEY_TARGET_GROUP, null)
            ?: BuildConfig.TARGET_GROUPS
        val individuals = p.getString(LEGACY_KEY_TARGET_INDIVIDUALS, null)
            ?: BuildConfig.TARGET_INDIVIDUALS
        val senders = p.getString(LEGACY_KEY_ALLOWED_SENDERS, null)
            ?: BuildConfig.ALLOWED_SENDERS
        val prompts = p.getString(LEGACY_KEY_MATCH_PROMPTS, null)
            ?: p.getString(LEGACY_KEY_MATCH_PROMPT, null)
            ?: BuildConfig.MATCH_PROMPTS

        val preset = ConfigPreset.newEmpty("Default").copy(
            targetGroups = groups,
            targetIndividuals = individuals,
            allowedSenders = senders,
            matchPrompts = prompts
        )
        PresetStore.save(context, preset)
        PresetStore.setActive(context, preset.id)

        // Clean up legacy per-field keys — they've been folded into the preset.
        p.edit()
            .remove(LEGACY_KEY_TARGET_GROUPS)
            .remove(LEGACY_KEY_TARGET_INDIVIDUALS)
            .remove(LEGACY_KEY_ALLOWED_SENDERS)
            .remove(LEGACY_KEY_MATCH_PROMPTS)
            .remove(LEGACY_KEY_TARGET_GROUP)
            .remove(LEGACY_KEY_MATCH_PROMPT)
            .apply()
    }
}
