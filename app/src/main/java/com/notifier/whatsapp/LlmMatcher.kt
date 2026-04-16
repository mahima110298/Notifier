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

    suspend fun matchMessage(context: Context, message: WhatsAppMessage): LlmResult {
        val apiKey = AppConfig.getApiKey(context)
        val baseUrl = AppConfig.getApiBaseUrl(context)
        val model = AppConfig.getModel(context)
        val matchPrompt = AppConfig.getMatchPrompt(context)

        if (apiKey.isBlank() || apiKey == "your-api-key-here") {
            Log.w(TAG, "No API key configured, skipping LLM matching")
            return LlmResult(matches = true, reason = "LLM not configured — passing through")
        }

        val messageContent = buildString {
            append("Sender: ${message.sender}\n")
            append("Message: ${message.messageBody}\n")
            if (message.messages.isNotEmpty()) {
                append("Recent messages in thread:\n")
                message.messages.takeLast(5).forEach { append("  - $it\n") }
            }
        }

        val systemPrompt = """You are a message classifier. Your job is to determine if a WhatsApp message matches a specific criteria.

Criteria: $matchPrompt

Respond with ONLY a JSON object (no markdown, no code fences):
{"matches": true/false, "reason": "brief explanation"}"""

        val userPrompt = "Does this message match the criteria?\n\n$messageContent"

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

                LlmResult(
                    matches = result.optBoolean("matches", false),
                    reason = result.optString("reason", "No reason provided")
                )
            } catch (e: Exception) {
                Log.e(TAG, "LLM matching failed", e)
                LlmResult(false, "Error: ${e.message}")
            }
        }
    }
}
