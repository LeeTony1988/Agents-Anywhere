package com.agentsanywhere.app.feature.remotedesktop.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IME leg of the keyboard: what the soft keyboard commits becomes `text` events
 * (`input-event.schema.json`), split at 4096 code points without ever cutting a surrogate
 * pair, and never carrying sticky modifiers, which the schema does not allow on `text`.
 */
class RemoteTextInputTest {

    private val sent = mutableListOf<String>()
    private val dispatcher = RemoteInputDispatcher(
        sink = object : RemoteInputSink {
            override fun sendInput(json: String) {
                sent += json
            }

            override fun sendPointerFrame(bytes: ByteArray) = Unit
        },
    )

    @Test
    fun aCommittedStringBecomesOneTextEvent() {
        dispatcher.sendText("hello")

        assertEquals(1, sent.size)
        val event = JSONObject(sent.single())
        assertEquals("text", event.getString("t"))
        assertEquals("hello", event.getString("s"))
        assertEquals(1, event.getInt("v"))
    }

    @Test
    fun emptyTextSendsNothing() {
        dispatcher.sendText("")

        assertTrue(sent.isEmpty())
    }

    @Test
    fun longTextIsSplitIntoSchemaSizedChunks() {
        val text = "a".repeat(REMOTE_INPUT_MAX_TEXT_CODE_POINTS + 500)

        dispatcher.sendText(text)

        assertEquals(2, sent.size)
        val first = JSONObject(sent[0]).getString("s")
        val second = JSONObject(sent[1]).getString("s")
        assertEquals(REMOTE_INPUT_MAX_TEXT_CODE_POINTS, first.length)
        assertEquals(500, second.length)
        assertEquals(text, first + second)
    }

    @Test
    fun aSplitNeverCutsASurrogatePair() {
        // 4095 BMP characters followed by an emoji: the 4096th "character" would be half of
        // the surrogate pair if the chunker counted UTF-16 units instead of code points.
        val text = "a".repeat(REMOTE_INPUT_MAX_TEXT_CODE_POINTS - 1) + "\uD83D\uDE00" + "tail"

        dispatcher.sendText(text)

        assertEquals(2, sent.size)
        val first = JSONObject(sent[0]).getString("s")
        assertTrue("The emoji must stay whole", first.endsWith("\uD83D\uDE00"))
        assertEquals("tail", JSONObject(sent[1]).getString("s"))
    }

    @Test
    fun textEventsAreSequencedIndependentlyOfKeys() {
        dispatcher.sendText("abc")
        dispatcher.sendKey("KeyA", down = true)

        val textSeq = JSONObject(sent[0]).getLong("seq")
        val keySeq = JSONObject(sent[1]).getLong("seq")
        assertTrue("Every aa.input event carries its own seq", keySeq > textSeq)
    }

    @Test
    fun stickyModifiersAreNotAttachedToTextEvents() {
        dispatcher.modifiers.toggle(RemoteModifierKey.Ctrl)

        dispatcher.sendText("x")

        assertTrue("text has no mods field", !JSONObject(sent.single()).has("mods"))
        assertEquals(
            "The modifier stays armed for the next key event",
            listOf(RemoteModifierKey.Ctrl),
            dispatcher.modifiers.active,
        )
    }
}
