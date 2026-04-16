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

object LlmMatcher {
    private const val TAG = "LlmMatcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class LlmResult(
        val matches: Boolean,
        val reason: String
    )

    /** Backwards-compat: read prompts from the editing preset via AppConfig. */
    suspend fun matchMessage(context: Context, message: WhatsAppMessage): LlmResult =
        matchMessage(context, message, AppConfig.getMatchPrompts(context))

    /** Explicit prompts — used when iterating enabled presets. */
    suspend fun matchMessage(
        context: Context,
        message: WhatsAppMessage,
        prompts: List<String>
    ): LlmResult {
        val apiKey = AppConfig.getApiKey(context)
        val baseUrl = AppConfig.getApiBaseUrl(context)
        val model = AppConfig.getModel(context)

        if (apiKey.isBlank() || apiKey == "your-api-key-here") {
            Log.w(TAG, "No API key configured, skipping LLM matching")
            return LlmResult(matches = true, reason = "LLM not configured — passing through")
        }

        if (prompts.isEmpty()) {
            Log.w(TAG, "No match prompts configured — treating everything as a match")
            return LlmResult(matches = true, reason = "No match prompts configured")
        }

        val messageContent = buildString {
            append("Sender: ${message.sender}\n")
            append("Message: ${message.messageBody}\n")
            if (message.messages.isNotEmpty()) {
                append("Recent messages in thread:\n")
                message.messages.takeLast(5).forEach { append("  - $it\n") }
            }
        }

        // Single criterion → ask directly. Multiple → OR them and ask the
        // LLM to report which one matched.
        val systemPrompt = if (prompts.size == 1) {
            """You are a message classifier. Your job is to determine if a WhatsApp message matches a specific criterion.

Criterion: ${prompts[0]}

Respond with ONLY a JSON object (no markdown, no code fences):
{"matches": true/false, "reason": "brief explanation"}"""
        } else {
            val numbered = prompts.mapIndexed { i, p -> "${i + 1}. $p" }.joinToString("\n")
            """You are a message classifier. Your job is to determine if a WhatsApp message matches ANY of the following criteria.

Criteria (a message matches if ANY one applies):
$numbered

Respond with ONLY a JSON object (no markdown, no code fences):
{"matches": true/false, "matched_criterion": <1-based number of the matching criterion, or 0>, "reason": "brief explanation"}"""
        }

        val userPrompt = if (prompts.size == 1) {
            "Does this message match the criterion?\n\n$messageContent"
        } else {
            "Does this message match any of the criteria?\n\n$messageContent"
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", userPrompt)
                        })
                    })
                    put("temperature", 0.1)
                    put("max_tokens", 150)
                }

                val url = "${baseUrl.trimEnd('/')}/chat/completions"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.e(TAG, "LLM API error ${response.code}: $body")
                    return@withContext LlmResult(false, "API error: ${response.code}")
                }

                val jsonResponse = JSONObject(body)
                val content = jsonResponse
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim()

                // Parse the JSON response from LLM
                val cleanContent = content
                    .removePrefix("```json")
                    .removePrefix("```")
                    .removeSuffix("```")
                    .trim()
                val result = JSONObject(cleanContent)

                val reason = result.optString("reason", "No reason provided")
                val matchedNum = result.optInt("matched_criterion", 0)
                val enrichedReason = if (prompts.size > 1 && matchedNum in 1..prompts.size) {
                    "[criterion #$matchedNum: ${prompts[matchedNum - 1]}] $reason"
                } else {
                    reason
                }

                LlmResult(
                    matches = result.optBoolean("matches", false),
                    reason = enrichedReason
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM matching failed", e)
                LlmResult(false, "Error: ${e.message}")
            }
        }
    }
}
