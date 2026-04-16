package com.notifier.whatsapp

import android.content.Context
import android.util.Log
import org.json.JSONArray

/**
 * Persistent store of named configuration presets. Backed by a single
 * SharedPreferences file (separate from the legacy per-field config).
 *
 * There is always 0 or 1 "active" preset — its id is stored under
 * KEY_ACTIVE_ID. AppConfig.getTargetGroups/… read from the active preset.
 */
object PresetStore {
    private const val TAG = "PresetStore"
    private const val PREFS_NAME = "config_presets"
    private const val KEY_LIST = "list_json"
    private const val KEY_ACTIVE_ID = "active_id"

    private val lock = Any()

    fun list(ctx: Context): List<ConfigPreset> = synchronized(lock) {
        val raw = prefs(ctx).getString(KEY_LIST, null) ?: return emptyList()
        val arr = try { JSONArray(raw) } catch (e: Exception) {
            Log.w(TAG, "Failed to parse presets list JSON; returning empty", e)
            return emptyList()
        }
        (0 until arr.length()).map { ConfigPreset.fromJson(arr.getJSONObject(it)) }
    }

    /** Subset currently participating in matching. Can contain 0 or many presets. */
    fun enabled(ctx: Context): List<ConfigPreset> = list(ctx).filter { it.enabled }

    fun activeId(ctx: Context): String? = prefs(ctx).getString(KEY_ACTIVE_ID, null)

    /** Returns the active preset, or the first preset if active_id is missing/stale, or null if none. */
    fun active(ctx: Context): ConfigPreset? {
        val all = list(ctx)
        if (all.isEmpty()) return null
        val id = activeId(ctx) ?: return all.first()
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun setActive(ctx: Context, id: String) = synchronized(lock) {
        prefs(ctx).edit().putString(KEY_ACTIVE_ID, id).apply()
        Log.d(TAG, "active preset = $id")
    }

    /** Insert or update by id. Does NOT change the active preset. */
    fun save(ctx: Context, preset: ConfigPreset) = synchronized(lock) {
        val current = list(ctx).toMutableList()
        val idx = current.indexOfFirst { it.id == preset.id }
        if (idx >= 0) current[idx] = preset else current.add(preset)
        writeList(ctx, current)
        Log.d(TAG, "saved id=${preset.id} name='${preset.name}' — total=${current.size}")
    }

    /** Remove by id. If it was active, promote the first remaining preset (if any) to active. */
    fun delete(ctx: Context, id: String) = synchronized(lock) {
        val remaining = list(ctx).filter { it.id != id }
        writeList(ctx, remaining)
        if (activeId(ctx) == id) {
            val newActiveId = remaining.firstOrNull()?.id
            if (newActiveId != null) {
                setActive(ctx, newActiveId)
            } else {
                prefs(ctx).edit().remove(KEY_ACTIVE_ID).apply()
            }
        }
        Log.d(TAG, "deleted id=$id; ${remaining.size} remain")
    }

    private fun writeList(ctx: Context, list: List<ConfigPreset>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs(ctx).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
