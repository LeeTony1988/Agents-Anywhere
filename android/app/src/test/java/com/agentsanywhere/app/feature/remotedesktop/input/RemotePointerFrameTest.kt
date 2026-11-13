package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Byte-for-byte checks against the hex vectors in
 * `contracts/remote-desktop/1.0/pointer-frame.md` section 4.
 */
class RemotePointerFrameTest {

    @Test
    fun normalMoveWithoutButtonsMatchesVector4_1() {
        val frame = encodePointerFrame(x = 0x8000, y = 0x4000, buttons = 0, flags = 0, seq = 1)

        assertFrame("01 00 00 00 00 80 00 40 01 00 00 00", frame)
        assertEquals(
            RemotePointerFrame(x = 0x8000, y = 0x4000, buttons = 0, flags = 0, seq = 1),
            decodePointerFrame(frame),
        )
    }

    @Test
    fun sequenceWrapAfter0xFFFFFFFFMatchesVector4_2() {
        val frame = encodePointerFrame(
            x = 0xFFFF,
            y = 0xFFFF,
            buttons = REMOTE_POINTER_BUTTON_LEFT,
            flags = REMOTE_POINTER_FLAG_BUTTONS_CHANGED,
            seq = 0L,
        )

        assertFrame("01 01 01 00 FF FF FF FF 00 00 00 00", frame)
        val decoded = requireNotNull(decodePointerFrame(frame))
        assertEquals(0L, decoded.seq)
        assertEquals(REMOTE_POINTER_BUTTON_LEFT, decoded.buttons)
        assertTrue(isNewerPointerSequence(candidate = 0L, current = 0xFFFFFFFFL))
    }

    @Test
    fun outOfRangeCoordinatesClampMatchesVector4_3() {
        val frame = encodePointerFrame(x = 70_000, y = -12, buttons = 0, flags = 0, seq = 42)

        assertFrame("01 00 00 00 FF FF 00 00 2A 00 00 00", frame)
        val decoded = requireNotNull(decodePointerFrame(frame))
        assertEquals(0xFFFF, decoded.x)
        assertEquals(0x0000, decoded.y)
        assertEquals(42L, decoded.seq)
    }

    @Test
    fun combinedButtonsMatchVector4_4() {
        val frame = encodePointerFrame(
            x = 0,
            y = 0x7FFF,
            buttons = REMOTE_POINTER_BUTTON_LEFT or REMOTE_POINTER_BUTTON_RIGHT or REMOTE_POINTER_BUTTON_MIDDLE,
            flags = REMOTE_POINTER_FLAG_BUTTONS_CHANGED,
            seq = 0xDEADBEEFL,
        )

        assertFrame("01 01 07 00 00 00 FF 7F EF BE AD DE", frame)
        val decoded = requireNotNull(decodePointerFrame(frame))
        assertEquals(0x0007, decoded.buttons)
        assertEquals(0x7FFF, decoded.y)
        assertEquals(0xDEADBEEFL, decoded.seq)
    }

    @Test
    fun buttonReleaseMatchesVector4_5() {
        val frame = encodePointerFrame(
            x = 0x1234,
            y = 0x5678,
            buttons = 0,
            flags = REMOTE_POINTER_FLAG_BUTTONS_CHANGED,
            seq = 43,
        )

        assertFrame("01 01 00 00 34 12 78 56 2B 00 00 00", frame)
        val decoded = requireNotNull(decodePointerFrame(frame))
        assertEquals(0x1234, decoded.x)
        assertEquals(0x5678, decoded.y)
        assertEquals(43L, decoded.seq)
    }

    @Test
    fun frameIsAlwaysTwelveBytes() {
        assertEquals(12, REMOTE_POINTER_FRAME_SIZE)
        assertEquals(12, encodePointerFrame(0, 0, 0, 0, 0).size)
    }

    @Test
    fun sequenceIsTakenModuloTwoToTheThirtySecond() {
        val wrapped = encodePointerFrame(x = 0, y = 0, buttons = 0, flags = 0, seq = 0x1_0000_0005L)

        assertEquals(5L, requireNotNull(decodePointerFrame(wrapped)).seq)
        assertEquals(0xFFFFFFFFL, requireNotNull(decodePointerFrame(encodePointerFrame(0, 0, 0, 0, -1L))).seq)
    }

    @Test
    fun reservedFlagAndButtonBitsAreDropped() {
        val decoded = requireNotNull(
            decodePointerFrame(encodePointerFrame(x = 1, y = 2, buttons = 0x00FF, flags = 0xFF, seq = 7)),
        )

        assertEquals(REMOTE_POINTER_FLAG_BUTTONS_CHANGED, decoded.flags)
        assertEquals(0x0007, decoded.buttons)
    }

    @Test
    fun decoderRejectsWrongTypeOrLength() {
        val valid = encodePointerFrame(1, 2, 0, 0, 3)
        val wrongType = valid.copyOf().also { it[0] = 0x02 }
        val short = valid.copyOf(11)

        assertNull(decodePointerFrame(wrongType))
        assertNull(decodePointerFrame(short))
        assertNull(decodePointerFrame(valid + 0x00.toByte()))
    }

    @Test
    fun newerThanUsesModularComparison() {
        assertTrue(isNewerPointerSequence(candidate = 2, current = 1))
        assertFalse(isNewerPointerSequence(candidate = 1, current = 1))
        assertFalse(isNewerPointerSequence(candidate = 1, current = 2))
        assertTrue(isNewerPointerSequence(candidate = 0, current = 0xFFFFFFFFL))
        assertFalse(isNewerPointerSequence(candidate = 0xFFFFFFFFL, current = 0))
    }

    @Test
    fun hexHelperRendersTheEncodedFrame() {
        val frame = RemotePointerFrame(x = 0x1234, y = 0x5678, buttons = 0, flags = 1, seq = 43)

        assertEquals("01 01 00 00 34 12 78 56 2B 00 00 00", frame.hexBytes())
        assertArrayEquals(frame.encode(), encodePointerFrame(0x1234, 0x5678, 0, 1, 43))
    }

    private fun assertFrame(expectedHex: String, actual: ByteArray) {
        val expected = expectedHex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("Expected a 12-byte frame", 12, expected.size)
        assertEquals("Unexpected frame length", expected.size, actual.size)
        expected.indices.forEach { offset ->
            assertEquals(
                "Byte at offset $offset differs: ${actual.toHex()} != $expectedHex",
                expected[offset],
                actual[offset],
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase() }
}
