package com.notifier.whatsapp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Turns a user's natural-language description of what they want alerts about
 * into a structured configuration (groups / individuals / senders / prompts).
 * Uses the same LLM endpoint as the matcher.
 */
object ConfigGenerator {
    private const val TAG = "ConfigGenerator"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Generated(
        val targetGroups: String,
        val targetIndividuals: String,
        val allowedSenders: String,
        val matchPrompts: String,
        val rationale: String,
    )

    suspend fun generate(context: Context, naturalLanguage: String): Result<Generated> {
        val apiKey = AppConfig.getApiKey(context)
        val baseUrl = AppConfig.getApiBaseUrl(context)
        val model = AppConfig.getModel(context)

        if (apiKey.isBlank() || apiKey == "your-api-key-here") {
            return Result.failure(IllegalStateException("LLM API key not configured. Set it in the Configuration section first."))
        }
        if (naturalLanguage.isBlank()) {
            return Result.failure(IllegalArgumentException("Description is empty"))
        }

        val systemPrompt = """You are a configuration assistant for a WhatsApp notification classifier.

The user will describe, in natural language, what kinds of WhatsApp messages should trigger alerts. Extract a structured configuration.

Output schema — respond with ONLY a JSON object (no markdown, no code fences):
{
  "target_groups": "<comma-separated WhatsApp group names, or empty string>",
  "target_individuals": "<comma-separated 1:1 contact names, or empty string>",
  "allowed_senders": "<comma-separated sender names to allow within groups, or the literal string 'all'>",
  "match_prompts": "<one or more classifier criteria, separated by '|' if multiple>",
  "rationale": "<one sentence explaining the mapping choices you made>"
}

Rules:
  • Preserve capitalization and spelling of names exactly as the user wrote them.
  • If the user mentions specific group names, put them in target_groups.
  • If they mention specific individuals for 1:1 monitoring, put those in target_individuals. Otherwise leave blank.
  • If they restrict to certain senders within groups (e.g. "only when Alice or Bob post"), put those in allowed_senders. Otherwise use "all".
  • match_prompts should be clear descriptions of the kinds of messages to alert on. Use multiple criteria (separated by '|') only when the user describes distinct unrelated triggers (e.g. "accommodation" AND "urgent outages").
  • Never leave match_prompts empty — if the user didn't specify, infer a best-effort criterion from their description.
  • Don't invent names that aren't in the user's description.
"""

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                        put(JSONObject().apply { put("role", "user"); put("content", naturalLanguage) })
                    })
                    put("temperature", 0.2)
                    put("max_tokens", 500)
                }

                val req = Request.Builder()
                    .url("${baseUrl.trimEnd('/')}/chat/completions")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        Log.e(TAG, "API ${resp.code}: $body")
                        return@withContext Result.failure(IllegalStateException("LLM API error ${resp.code}"))
                    }
                    val content = JSONObject(body)
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim()
                        .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    val j = JSONObject(content)
                    Result.success(
                        Generated(
                            targetGroups = j.optString("target_groups", "").trim(),
                            targetIndividuals = j.optString("target_individuals", "").trim(),
                            allowedSenders = j.optString("allowed_senders", "all").trim().ifBlank { "all" },
                            matchPrompts = j.optString("match_prompts", "").trim(),
                            rationale = j.optString("rationale", "").trim()
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation failed", e)
                Result.failure(e)
            }
        }
    }
}
