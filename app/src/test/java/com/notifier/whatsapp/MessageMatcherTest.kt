package com.notifier.whatsapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Data-driven tests that load test_cases.json from resources and exercise
 * MessageMatcher (group + sender filters) for each case. Each case supplies
 * an .env-style configuration (TARGET_GROUPS + ALLOWED_SENDERS + optional
 * TARGET_INDIVIDUALS + MATCH_PROMPTS) plus a WhatsApp message and the
 * expected filter outcomes.
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
            val actualGroup = MessageMatcher.matchesGroups(case.targetGroups, case.message)
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
    fun emptyTargetGroupsMatchesAnything() {
        val msg = fakeMessage(group = "Any Group", sender = "X", body = "hello")
        assertEquals(true, MessageMatcher.matchesGroups(emptyList(), msg))
    }

    @Test
    fun emptyAllowedSendersMatchesAnyone() {
        val msg = fakeMessage(group = "G", sender = "X", body = "hello")
        assertEquals(true, MessageMatcher.matchesSender(emptyList(), msg))
    }

    @Test
    fun multipleTargetGroups_anyMatches() {
        val targets = listOf("You n Me", "Project Team", "Flatmates")
        val a = fakeMessage(group = "Project Team", sender = "Alice", body = "hi")
        val b = fakeMessage(group = "Flatmates", sender = "Bob", body = "hi")
        val c = fakeMessage(group = "Other Group", sender = "Carol", body = "hi")
        assertEquals(true, MessageMatcher.matchesGroups(targets, a))
        assertEquals(true, MessageMatcher.matchesGroups(targets, b))
        assertEquals(false, MessageMatcher.matchesGroups(targets, c))
    }

    @Test
    fun multipleTargetGroups_caseInsensitive() {
        val targets = listOf("You n Me", "FLATMATES")
        val msg = fakeMessage(group = "flatmates", sender = "Alice", body = "hi")
        assertEquals(true, MessageMatcher.matchesGroups(targets, msg))
    }

    @Test
    fun matchesIndividual_wildcardAlwaysTrue() {
        val msg = directMessage(from = "Random Contact", body = "hi")
        assertEquals(true, MessageMatcher.matchesIndividual(emptyList(), wildcard = true, msg))
    }

    @Test
    fun matchesIndividual_emptyListRejects() {
        // Opt-in: blank individuals → no 1:1 messages alert.
        val msg = directMessage(from = "Alice", body = "hi")
        assertEquals(false, MessageMatcher.matchesIndividual(emptyList(), wildcard = false, msg))
    }

    @Test
    fun matchesIndividual_allowedContactMatches() {
        val msg = directMessage(from = "Alice", body = "hi")
        assertEquals(true, MessageMatcher.matchesIndividual(listOf("Alice", "Bob"), wildcard = false, msg))
    }

    @Test
    fun matchesIndividual_disallowedContactRejected() {
        val msg = directMessage(from = "Carol", body = "hi")
        assertEquals(false, MessageMatcher.matchesIndividual(listOf("Alice", "Bob"), wildcard = false, msg))
    }

    @Test
    fun matchesIndividual_substringMatch() {
        // "Alice" is a substring of "Alice Smith" — matches.
        val msg = directMessage(from = "Alice Smith", body = "hi")
        assertEquals(true, MessageMatcher.matchesIndividual(listOf("Alice"), wildcard = false, msg))
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

    private fun directMessage(from: String, body: String) = WhatsAppMessage(
        title = from,          // 1:1: title is the contact
        text = body,
        bigText = body,
        subText = "",
        sender = from,
        messageBody = body,
        messages = emptyList(),
        timestamp = 0L,
        isGroupMessage = false
    )
}

/** Shared fixture loader used by the matcher tests. */
internal object TestFixtures {
    data class Case(
        val name: String,
        val targetGroups: List<String>,
        val allowedSenders: List<String>,
        val matchPrompt: String,      // first prompt — used by the LLM integration test
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

            // Prefer plural TARGET_GROUPS; fall back to singular TARGET_GROUP.
            val groupsRaw = when {
                config.has("TARGET_GROUPS") -> config.optString("TARGET_GROUPS", "")
                config.has("TARGET_GROUP") -> config.optString("TARGET_GROUP", "")
                else -> ""
            }
            val groups = parseList(groupsRaw)

            val sendersRaw = config.optString("ALLOWED_SENDERS", "all")
            val senders = parseList(sendersRaw)

            // Prefer plural MATCH_PROMPTS (first entry); fall back to singular.
            val promptsRaw = when {
                config.has("MATCH_PROMPTS") -> config.optString("MATCH_PROMPTS", "")
                config.has("MATCH_PROMPT") -> config.optString("MATCH_PROMPT", "")
                else -> ""
            }
            val firstPrompt = promptsRaw.split('\n', '|').map { it.trim() }.firstOrNull { it.isNotBlank() } ?: ""

            Case(
                name = raw.getString("name"),
                targetGroups = groups,
                allowedSenders = senders,
                matchPrompt = firstPrompt,
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

    private fun parseList(raw: String): List<String> {
        if (raw.isBlank() || raw.equals("all", ignoreCase = true)) return emptyList()
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}
