package com.agentsanywhere.app.feature.remotedesktop.input

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field names and enum values here are the ones in
 * `contracts/remote-desktop/1.0/schemas/input-event.schema.json`; the fixtures under
 * `fixtures/valid/input-event/` use the same shapes.
 */
class RemoteInputEncoderTest {

    private val fixedNow = 1_762_944_000_123L

    private fun encoder(startAt: Long = 0L): RemoteInputEncoder {
        var next = startAt
        return RemoteInputEncoder(nextSeq = { next++ }, clock = { fixedNow })
    }

    @Test
    fun pointerMoveMatchesTheSchemaShape() {
        val event = encoder().pointerEvent(
            seq = 42,
            action = PointerAction.Move,
            x = 0.512f,
            y = 0.331f,
        )

        assertEquals(setOf("v", "seq", "ts", "t", "a", "x", "y"), event.props())
        assertEquals(1, event.getInt("v"))
        assertEquals(42L, event.getLong("seq"))
        assertEquals(fixedNow, event.getLong("ts"))
        assertEquals("pointer", event.getString("t"))
        assertEquals("move", event.getString("a"))
        assertEquals(0.512, event.getDouble("x"), 1e-6)
        assertEquals(0.331, event.getDouble("y"), 1e-6)
        assertFalse("A move carries no button", event.has("b"))
    }

    @Test
    fun pointerDownAndUpCarryTheButtonAndDisplay() {
        val down = encoder().pointerEvent(
            seq = 1,
            action = PointerAction.Down,
            x = 0.5f,
            y = 0.5f,
            button = PointerButton.Right,
            display = 1,
        )
        val up = encoder().pointerEvent(
            seq = 2,
            action = PointerAction.Up,
            x = 0.5f,
            y = 0.5f,
        )

        assertEquals("down", down.getString("a"))
        assertEquals("right", down.getString("b"))
        assertEquals(1, down.getInt("d"))
        // A down/up without an explicit button is the left button; the schema requires `b`.
        assertEquals("left", up.getString("b"))
    }

    @Test
    fun pointerCoordinatesAndDisplayAreClamped() {
        val event = encoder().pointerEvent(
            seq = 3,
            action = PointerAction.Move,
            x = 4.5f,
            y = -2f,
            display = 99,
        )

        assertEquals(1.0, event.getDouble("x"), 0.0)
        assertEquals(0.0, event.getDouble("y"), 0.0)
        assertEquals(15, event.getInt("d"))
    }

    @Test
    fun wheelCarriesUnitAndFallsBackToPixels() {
        val pixels = encoder().wheelEvent(seq = 46, dx = 0f, dy = -120f)
        val lines = encoder().wheelEvent(seq = 47, dx = 1f, dy = 2f, unit = "line")
        val unknown = encoder().wheelEvent(seq = 48, dx = 0f, dy = 0f, unit = "furlong")

        assertEquals(setOf("v", "seq", "ts", "t", "dx", "dy", "unit", "d"), pixels.props())
        assertEquals("wheel", pixels.getString("t"))
        assertEquals(-120.0, pixels.getDouble("dy"), 0.0)
        assertEquals("pixel", pixels.getString("unit"))
        assertEquals("line", lines.getString("unit"))
        assertEquals("pixel", unknown.getString("unit"))
    }

    @Test
    fun keyEventFiltersModifiersAndKeepsRepeat() {
        val down = encoder().keyEvent(
            seq = 41,
            down = true,
            code = "KeyC",
            mods = listOf("ctrl", "shift", "hyper", "ctrl"),
            repeat = false,
        )
        val repeat = encoder().keyEvent(seq = 42, down = true, code = "KeyC", mods = emptyList(), repeat = true)
        val up = encoder().keyEvent(seq = 43, down = false, code = "KeyC")

        assertEquals(setOf("v", "seq", "ts", "t", "a", "code", "mods", "repeat"), down.props())
        assertEquals("key", down.getString("t"))
        assertEquals("down", down.getString("a"))
        assertEquals("KeyC", down.getString("code"))
        assertEquals(listOf("ctrl", "shift"), down.getJSONArray("mods").toList())
        assertFalse(down.getBoolean("repeat"))
        assertTrue(repeat.getBoolean("repeat"))
        assertEquals("up", up.getString("a"))
        assertEquals(emptyList<String>(), up.getJSONArray("mods").toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun keyEventRejectsCodesTheSchemaWouldRefuse() {
        encoder().keyEvent(seq = 1, down = true, code = "Ctrl+C")
    }

    @Test
    fun textEventTruncatesAtTheSchemaLimit() {
        val atLimit = encoder().textEvent(seq = 1, text = "a".repeat(REMOTE_INPUT_MAX_TEXT_CODE_POINTS))
        val overLimit = encoder().textEvent(seq = 2, text = "a".repeat(REMOTE_INPUT_MAX_TEXT_CODE_POINTS + 1))

        assertEquals(4096, atLimit.getString("s").length)
        assertEquals(4096, overLimit.getString("s").length)
        assertEquals(setOf("v", "seq", "ts", "t", "s"), atLimit.props())
        assertEquals("text", atLimit.getString("t"))
    }

    @Test
    fun textEventsSplitOnTheChunkBoundary() {
        val exactlyOne = encoder().textEvents("a".repeat(4096))
        val split = encoder().textEvents("a".repeat(4097))
        val twoFullChunks = encoder().textEvents("a".repeat(8192))

        assertEquals(1, exactlyOne.size)
        assertEquals(listOf(4096, 1), split.map { it.getString("s").length })
        assertEquals(listOf(4096, 4096), twoFullChunks.map { it.getString("s").length })
        assertEquals(emptyList<JSONObject>(), encoder().textEvents(""))
    }

    @Test
    fun textEventsNeverSplitASurrogatePair() {
        val emoji = "😀"
        val chunks = encoder().textEvents(emoji.repeat(4097))

        assertEquals(2, chunks.size)
        assertEquals(4096, chunks[0].getString("s").codePointCount(0, chunks[0].getString("s").length))
        assertEquals(emoji, chunks[1].getString("s"))
        assertEquals("😀".repeat(4097), chunks.joinToString("") { it.getString("s") })
    }

    @Test
    fun textEventsKeepSequenceOrderOnWrap() {
        val encoder = encoder(startAt = 0xFFFFFFFFL)
        val chunks = encoder.textEvents("a".repeat(4097))

        assertEquals(0xFFFFFFFFL, chunks[0].getLong("seq"))
        assertEquals(0L, chunks[1].getLong("seq"))
    }

    @Test
    fun pingHasNoSequence() {
        val ping = encoder().pingEvent(at = 1_762_944_000_200L)

        assertEquals(setOf("v", "t", "at"), ping.props())
        assertEquals(1, ping.getInt("v"))
        assertEquals("ping", ping.getString("t"))
        assertEquals(1_762_944_000_200L, ping.getLong("at"))
    }

    @Test
    fun releaseAllHasNoSequenceAndAcceptsOnlyContractReasons() {
        val blur = encoder().releaseAllEvent(RELEASE_REASON_BLUR)
        val handoff = encoder().releaseAllEvent(RELEASE_REASON_HANDOFF)

        assertEquals(setOf("v", "t", "reason"), blur.props())
        assertEquals("release_all", blur.getString("t"))
        assertEquals("blur", blur.getString("reason"))
        assertEquals("handoff", handoff.getString("reason"))
        assertFalse(blur.has("seq"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun releaseAllRejectsUnknownReasons() {
        encoder().releaseAllEvent("because")
    }

    @Test
    fun sequencesWrapAtThirtyTwoBits() {
        val encoder = encoder(startAt = 0xFFFFFFFFL)

        assertEquals(0xFFFFFFFFL, encoder.nextSequence())
        assertEquals(0L, encoder.nextSequence())
        assertEquals(1L, encoder.nextSequence())
        // An explicit seq is masked too, so a plain Long counter never leaks past 32 bits.
        assertEquals(5L, encoder.pointerEvent(0x1_0000_0005L, PointerAction.Move, 0f, 0f).getLong("seq"))
    }

    private fun JSONObject.props(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    private fun org.json.JSONArray.toList(): List<String> = List(length()) { index -> optString(index) }
}
