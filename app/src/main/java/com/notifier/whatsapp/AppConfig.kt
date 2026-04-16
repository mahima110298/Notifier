package com.notifier.whatsapp

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages app configuration backed by SharedPreferences.
 * Defaults are loaded from BuildConfig (which reads .env at build time).
 * Users can override via the UI, which persists to SharedPreferences.
 */
object AppConfig {
    private const val PREFS_NAME = "whatsapp_notifier_config"
    private const val KEY_TARGET_GROUP = "target_group"
    private const val KEY_ALLOWED_SENDERS = "allowed_senders"
    private const val KEY_API_KEY = "llm_api_key"
    private const val KEY_API_BASE_URL = "llm_api_base_url"
    private const val KEY_MODEL = "llm_model"
    private const val KEY_MATCH_PROMPT = "match_prompt"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTargetGroup(context: Context): String =
        prefs(context).getString(KEY_TARGET_GROUP, null)
            ?: BuildConfig.TARGET_GROUP

    fun getAllowedSenders(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ALLOWED_SENDERS, null)
            ?: BuildConfig.ALLOWED_SENDERS
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun getApiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, null)
            ?: BuildConfig.LLM_API_KEY

    fun getApiBaseUrl(context: Context): String =
        prefs(context).getString(KEY_API_BASE_URL, null)
            ?: BuildConfig.LLM_API_BASE_URL

    fun getModel(context: Context): String =
        prefs(context).getString(KEY_MODEL, null)
            ?: BuildConfig.LLM_MODEL

    fun getMatchPrompt(context: Context): String =
        prefs(context).getString(KEY_MATCH_PROMPT, null)
            ?: BuildConfig.MATCH_PROMPT

    fun save(
        context: Context,
        targetGroup: String,
        allowedSenders: String,
        apiKey: String,
        apiBaseUrl: String,
        model: String,
        matchPrompt: String
    ) {
        prefs(context).edit()
            .putString(KEY_TARGET_GROUP, targetGroup)
            .putString(KEY_ALLOWED_SENDERS, allowedSenders)
            .putString(KEY_API_KEY, apiKey)
            .putString(KEY_API_BASE_URL, apiBaseUrl)
            .putString(KEY_MODEL, model)
            .putString(KEY_MATCH_PROMPT, matchPrompt)
            .apply()
    }
}
