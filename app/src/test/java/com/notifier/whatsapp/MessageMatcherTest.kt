package com.notifier.whatsapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Data-driven tests that load test_cases.json from resources and exercise
 * MessageMatcher (group + sender filters) for each case. Each case supplies
 * an .env-style configuration (TARGET_GROUP, ALLOWED_SENDERS, MATCH_PROMPT)
 * plus a WhatsApp message and the expected filter outcomes.
 *
 * The LLM-matching portion of the pipeline is covered separately in
 * LlmMatcherIntegrationTest, which hits the real API and is gated on
 * RUN_LLM_TESTS=1.
 */
class MessageMatcherTest {

    @Test
    fun runAllCasesFromJson() {
        val cases = TestFixtures.load()
        val failures = mutableListOf<String>()

        for (case in cases) {
            val actualGroup = MessageMatcher.matchesGroup(case.targetGroup, case.message)
            val actualSender = MessageMatcher.matchesSender(case.allowedSenders, case.message)

            if (actualGroup != case.expectedGroupMatch) {
                failures += "[${case.name}] group_match: expected=${case.expectedGroupMatch} actual=$actualGroup"
            }
            if (actualSender != case.expectedSenderMatch) {
                failures += "[${case.name}] sender_match: expected=${case.expectedSenderMatch} actual=$actualSender"
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Failed ${failures.size} of ${cases.size} cases:\n  " + failures.joinToString("\n  ")
            )
        }
    }

    @Test
    fun blankTargetGroupMatchesAnything() {
        val msg = fakeMessage(group = "Any Group", sender = "X", body = "hello")
        assertEquals(true, MessageMatcher.matchesGroup("", msg))
    }

    @Test
    fun emptyAllowedSendersMatchesAnyone() {
        val msg = fakeMessage(group = "G", sender = "X", body = "hello")
        assertEquals(true, MessageMatcher.matchesSender(emptyList(), msg))
    }

    private fun fakeMessage(group: String, sender: String, body: String) = WhatsAppMessage(
        title = group,
        text = "$sender: $body",
        bigText = body,
        subText = group,
        sender = sender,
        messageBody = body,
        messages = emptyList(),
        timestamp = 0L,
        isGroupMessage = true
    )
}

/** Shared fixture loader used by the matcher tests. */
internal object TestFixtures {
    data class Case(
        val name: String,
        val targetGroup: String,
        val allowedSenders: List<String>,
        val matchPrompt: String,
        val message: WhatsAppMessage,
        val expectedGroupMatch: Boolean,
        val expectedSenderMatch: Boolean,
        val expectedLlmMatch: Boolean?
    )

    fun load(): List<Case> {
        val json = javaClass.classLoader!!
            .getResourceAsStream("test_cases.json")!!
            .bufferedReader()
            .use { it.readText() }

        val root = JSONObject(json)
        val arr = root.getJSONArray("test_cases")
        return (0 until arr.length()).map { i ->
            val raw = arr.getJSONObject(i)
            val config = raw.getJSONObject("config")
            val msg = raw.getJSONObject("message")
            val exp = raw.getJSONObject("expected")

            val rawSenders = config.optString("ALLOWED_SENDERS", "all")
            val senders = if (rawSenders.isBlank() || rawSenders.equals("all", ignoreCase = true)) {
                emptyList()
            } else {
                rawSenders.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }

            Case(
                name = raw.getString("name"),
                targetGroup = config.optString("TARGET_GROUP", ""),
                allowedSenders = senders,
                matchPrompt = config.optString("MATCH_PROMPT", ""),
                message = WhatsAppMessage(
                    title = msg.optString("title", ""),
                    text = msg.optString("text", ""),
                    bigText = msg.optString("bigText", ""),
                    subText = msg.optString("subText", ""),
                    sender = msg.optString("sender", ""),
                    messageBody = msg.optString("messageBody", ""),
                    messages = emptyList(),
                    timestamp = 0L,
                    isGroupMessage = msg.optBoolean("isGroupMessage", true)
                ),
                expectedGroupMatch = exp.getBoolean("group_match"),
                expectedSenderMatch = exp.getBoolean("sender_match"),
                expectedLlmMatch = if (exp.has("llm_match")) exp.getBoolean("llm_match") else null
            )
        }
    }
}
