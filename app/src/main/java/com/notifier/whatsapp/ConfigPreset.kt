package com.notifier.whatsapp

import org.json.JSONObject
import java.util.UUID

/**
 * A named, reusable configuration profile — groups, individuals, sender
 * allow-list, and match prompts bundled together. LLM endpoint config
 * (api key / base URL / model) is intentionally NOT part of a preset; it
 * stays global.
 *
 * `enabled` controls whether this preset participates in runtime matching.
 * Multiple presets can be enabled at once — the listener iterates all
 * enabled presets and alerts on the first one whose filters + LLM approve
 * an incoming message.
 */
data class ConfigPreset(
    val id: String,
    val name: String,
    val enabled: Boolean = true,
    val targetGroups: String,
    val targetIndividuals: String,
    val allowedSenders: String,
    val matchPrompts: String,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("target_groups", targetGroups)
        put("target_individuals", targetIndividuals)
        put("allowed_senders", allowedSenders)
        put("match_prompts", matchPrompts)
    }

    // ---- Field parsing helpers (CSV / newline-separated) ----

    /** Parsed group-name whitelist. Empty = match any group. */
    fun targetGroupsList(): List<String> = parseCsv(targetGroups)

    /** Parsed individuals whitelist — see [individualsWildcard] for "all" handling. */
    fun targetIndividualsList(): List<String> {
        val raw = targetIndividuals
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Does this preset explicitly opt in to every 1:1 contact? */
    fun individualsWildcard(): Boolean =
        targetIndividuals.trim().equals("all", ignoreCase = true)

    /** Parsed within-group sender allow-list. Empty = any sender. */
    fun allowedSendersList(): List<String> = parseCsv(allowedSenders)

    /** Parsed LLM criteria. Supports both '\n' (UI) and '|' (.env) separators. */
    fun matchPromptsList(): List<String> =
        matchPrompts.split('\n', '|').map { it.trim() }.filter { it.isNotBlank() }

    private fun parseCsv(raw: String): List<String> {
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    companion object {
        fun newEmpty(name: String): ConfigPreset = ConfigPreset(
            id = UUID.randomUUID().toString(),
            name = name,
            enabled = true,
            targetGroups = "",
            targetIndividuals = "",
            allowedSenders = "all",
            matchPrompts = ""
        )

        fun fromJson(o: JSONObject): ConfigPreset = ConfigPreset(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name", "Unnamed"),
            // Older presets without the field default to enabled=true so
            // upgrading users don't silently lose their alerts.
            enabled = if (o.has("enabled")) o.optBoolean("enabled", true) else true,
            targetGroups = o.optString("target_groups", ""),
            targetIndividuals = o.optString("target_individuals", ""),
            allowedSenders = o.optString("allowed_senders", "all"),
            matchPrompts = o.optString("match_prompts", "")
        )
    }
}
