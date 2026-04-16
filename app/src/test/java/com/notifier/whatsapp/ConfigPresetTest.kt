package com.notifier.whatsapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPresetTest {

    @Test fun json_roundTrip_preservesAllFields() {
        val p = ConfigPreset(
            id = "abc-123",
            name = "Work",
            enabled = true,
            targetGroups = "You n Me, Project Team",
            targetIndividuals = "Alice, Bob",
            allowedSenders = "all",
            matchPrompts = "accommodation requests\nurgent outages"
        )
        val roundTripped = ConfigPreset.fromJson(p.toJson())
        assertEquals(p, roundTripped)
    }

    @Test fun json_roundTrip_preservesDisabledState() {
        val p = ConfigPreset.newEmpty("Paused").copy(enabled = false)
        assertEquals(false, ConfigPreset.fromJson(p.toJson()).enabled)
    }

    @Test fun fromJson_missingEnabledFieldDefaultsToTrue() {
        // Upgrade path from pre-enabled-flag data: existing presets must
        // continue alerting rather than going silent.
        val legacy = JSONObject().apply {
            put("id", "old-1")
            put("name", "Pre-existing")
            put("target_groups", "")
            put("target_individuals", "")
            put("allowed_senders", "all")
            put("match_prompts", "")
            // no "enabled" field
        }
        assertEquals(true, ConfigPreset.fromJson(legacy).enabled)
    }

    @Test fun parsingHelpers_returnExpectedLists() {
        val p = ConfigPreset.newEmpty("T").copy(
            targetGroups = "A, B, C",
            targetIndividuals = "Alice",
            allowedSenders = "all",
            matchPrompts = "foo\nbar|baz"
        )
        assertEquals(listOf("A", "B", "C"), p.targetGroupsList())
        assertEquals(listOf("Alice"), p.targetIndividualsList())
        assertEquals(false, p.individualsWildcard())
        assertEquals(emptyList<String>(), p.allowedSendersList())       // "all" → empty
        assertEquals(listOf("foo", "bar", "baz"), p.matchPromptsList())
    }

    @Test fun parsingHelpers_handleWildcardIndividuals() {
        val p = ConfigPreset.newEmpty("T").copy(targetIndividuals = "all")
        assertEquals(true, p.individualsWildcard())
        assertEquals(emptyList<String>(), p.targetIndividualsList())
    }

    @Test fun newEmpty_hasUuidIdAndSensibleDefaults() {
        val p = ConfigPreset.newEmpty("Test")
        assertEquals("Test", p.name)
        assertEquals("", p.targetGroups)
        assertEquals("", p.targetIndividuals)
        assertEquals("all", p.allowedSenders)
        assertEquals("", p.matchPrompts)
        assertTrue("id should be a UUID-ish string", p.id.length >= 32)
    }

    @Test fun fromJson_missingFieldsGetSensibleDefaults() {
        // Only name is present.
        val o = JSONObject().apply { put("name", "Partial") }
        val p = ConfigPreset.fromJson(o)
        assertEquals("Partial", p.name)
        assertEquals("", p.targetGroups)
        assertEquals("", p.targetIndividuals)
        assertEquals("all", p.allowedSenders)
        assertEquals("", p.matchPrompts)
        assertNotNull(p.id)
        assertTrue(p.id.isNotBlank())
    }

    @Test fun fromJson_blankIdGetsReplacedWithUuid() {
        val o = JSONObject().apply { put("id", ""); put("name", "X") }
        val p = ConfigPreset.fromJson(o)
        assertTrue("blank id should be replaced", p.id.isNotBlank())
    }
}
