package com.notifier.whatsapp

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression tests for the "(N messages)" / "(N new messages)" suffix
 * stripping that WhatsApp appends to the title when multiple pending
 * messages are bundled into a single notification. Without stripping,
 * group-name equality ("You n Me" == "You n Me (2 messages)") fails and
 * every bundled notification is silently dropped by the group filter.
 */
class WhatsAppNotificationParserTest {

    private val P = WhatsAppNotificationParser

    @Test fun stripCountSuffix_plural_no_new() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me (2 messages)"))
        assertEquals("You n Me", P.stripCountSuffix("You n Me (15 messages)"))
    }

    @Test fun stripCountSuffix_singular_no_new() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me (1 message)"))
    }

    @Test fun stripCountSuffix_with_new() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me (1 new message)"))
        assertEquals("You n Me", P.stripCountSuffix("You n Me (7 new messages)"))
    }

    @Test fun stripCountSuffix_case_insensitive() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me (2 MESSAGES)"))
        assertEquals("You n Me", P.stripCountSuffix("You n Me (2 Messages)"))
    }

    @Test fun stripCountSuffix_no_suffix_preserved() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me"))
        assertEquals("Group", P.stripCountSuffix("Group"))
    }

    @Test fun stripCountSuffix_false_positive_preserved() {
        // A group literally named "My Group (2)" should not be stripped — no
        // "message(s)" keyword, so the suffix isn't a count indicator.
        assertEquals("My Group (2)", P.stripCountSuffix("My Group (2)"))
        // Same for "(no messages)" — no digit, so not a count indicator.
        assertEquals("Group (no messages)", P.stripCountSuffix("Group (no messages)"))
    }

    @Test fun stripCountSuffix_trims_whitespace() {
        assertEquals("You n Me", P.stripCountSuffix("You n Me  (2 messages)"))
        assertEquals("You n Me", P.stripCountSuffix("You n Me (2 messages)   "))
    }

    @Test fun getGroupName_strips_suffix_from_subText() {
        val msg = fakeMsg(title = "You n Me", subText = "You n Me (2 new messages)")
        assertEquals("You n Me", P.getGroupName(msg))
    }

    @Test fun getGroupName_strips_suffix_from_title_fallback() {
        // No subText — falls back to title.
        val msg = fakeMsg(title = "You n Me (3 messages)", subText = "")
        assertEquals("You n Me", P.getGroupName(msg))
    }

    @Test fun getGroupName_strips_suffix_from_sender_at_group() {
        val msg = fakeMsg(title = "Alice @ You n Me (2 messages)", subText = "")
        assertEquals("You n Me", P.getGroupName(msg))
    }

    @Test fun matchesGroup_ignores_count_suffix() {
        // The whole reason this test exists: make sure "You n Me" config
        // matches a notification whose raw title was "You n Me (2 messages)".
        val msg = fakeMsg(title = "You n Me (2 messages)", subText = "")
        assertEquals(true, MessageMatcher.matchesGroup("You n Me", msg))
        assertEquals(true, MessageMatcher.matchesGroup("you n me", msg)) // case-insensitive
    }

    private fun fakeMsg(
        title: String,
        subText: String = "",
        isGroup: Boolean = true,
        sender: String = "Alice",
        body: String = "hi"
    ) = WhatsAppMessage(
        title = title,
        text = "$sender: $body",
        bigText = body,
        subText = subText,
        sender = sender,
        messageBody = body,
        messages = emptyList(),
        timestamp = 0L,
        isGroupMessage = isGroup
    )
}
