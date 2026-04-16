package com.notifier.whatsapp

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * End-to-end test that hits the real LLM endpoint for every case in
 * test_cases.json that declares an `llm_match` expectation.
 *
 * Skipped by default. To run:
 *   RUN_LLM_TESTS=1 LLM_API_KEY=sk-... \
 *     [LLM_API_BASE_URL=https://api.openai.com/v1] [LLM_MODEL=gpt-4o-mini] \
 *     ./gradlew test
 *
 * We can't reuse LlmMatcher.matchMessage directly because it requires an
 * Android Context (to read AppConfig). This test duplicates the request
 * shape so it can run on plain JVM.
 */
class LlmMatcherIntegrationTest {

    @Test
    fun everyCaseWithLlmExpectation() {
        val enabled = System.getenv("RUN_LLM_TESTS") == "1"
        assumeTrue("LLM tests disabled (set RUN_LLM_TESTS=1 to enable)", enabled)

        val apiKey = System.getenv("LLM_API_KEY").orEmpty()
        assumeTrue("LLM_API_KEY not set", apiKey.isNotBlank())

        val baseUrl = System.getenv("LLM_API_BASE_URL") ?: "https://api.openai.com/v1"
        val model = System.getenv("LLM_MODEL") ?: "gpt-4o-mini"

        val cases = TestFixtures.load().filter { it.expectedLlmMatch != null }
        assumeTrue("No cases declare an llm_match expectation", cases.isNotEmpty())

        val failures = mutableListOf<String>()
        for (case in cases) {
            val actual = runBlocking { classify(apiKey, baseUrl, model, case.matchPrompt, case.message) }
            if (actual != case.expectedLlmMatch) {
                failures += "[${case.name}] llm_match: expected=${case.expectedLlmMatch} actual=$actual"
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Failed ${failures.size} of ${cases.size} LLM cases:\n  " + failures.joinToString("\n  ")
            )
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private suspend fun classify(
        apiKey: String,
        baseUrl: String,
        model: String,
        matchPrompt: String,
        message: WhatsAppMessage
    ): Boolean {
        val systemPrompt = """You are a message classifier. Your job is to determine if a WhatsApp message matches a specific criteria.

Criteria: $matchPrompt

Respond with ONLY a JSON object (no markdown, no code fences):
{"matches": true/false, "reason": "brief explanation"}"""
        val userPrompt = "Does this message match the criteria?\n\nSender: ${message.sender}\nMessage: ${message.messageBody}\n"

        val body = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userPrompt) })
            })
            put("temperature", 0.1)
            put("max_tokens", 150)
        }

        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            check(resp.isSuccessful) { "LLM API ${resp.code}: $raw" }
            val content = JSONObject(raw)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content").trim()
                .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return JSONObject(content).optBoolean("matches", false)
        }
    }
}
