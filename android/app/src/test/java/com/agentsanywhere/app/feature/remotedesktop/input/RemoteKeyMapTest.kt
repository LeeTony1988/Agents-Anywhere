package com.agentsanywhere.app.feature.remotedesktop.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mapping table is plain Kotlin: `KeyEvent.KEYCODE_*` are compile-time constants, so
 * these tests run on the JVM without a `KeyEvent` instance or a device.
 */
class RemoteKeyMapTest {

    @Test
    fun lettersMapToKeyAThroughKeyZ() {
        assertEquals("KeyA", remoteKeyboardCode(KeyEvent.KEYCODE_A))
        assertEquals("KeyC", remoteKeyboardCode(KeyEvent.KEYCODE_C))
        assertEquals("KeyM", remoteKeyboardCode(KeyEvent.KEYCODE_M))
        assertEquals("KeyZ", remoteKeyboardCode(KeyEvent.KEYCODE_Z))
    }

    @Test
    fun digitsMapToDigit0ThroughDigit9() {
        assertEquals("Digit0", remoteKeyboardCode(KeyEvent.KEYCODE_0))
        assertEquals("Digit1", remoteKeyboardCode(KeyEvent.KEYCODE_1))
        assertEquals("Digit9", remoteKeyboardCode(KeyEvent.KEYCODE_9))
    }

    @Test
    fun navigationAndEditingKeysMatchTheW3cNames() {
        val expected = mapOf(
            KeyEvent.KEYCODE_DPAD_UP to "ArrowUp",
            KeyEvent.KEYCODE_DPAD_DOWN to "ArrowDown",
            KeyEvent.KEYCODE_DPAD_LEFT to "ArrowLeft",
            KeyEvent.KEYCODE_DPAD_RIGHT to "ArrowRight",
            KeyEvent.KEYCODE_ENTER to "Enter",
            KeyEvent.KEYCODE_NUMPAD_ENTER to "NumpadEnter",
            KeyEvent.KEYCODE_DEL to "Backspace",
            KeyEvent.KEYCODE_FORWARD_DEL to "Delete",
            KeyEvent.KEYCODE_TAB to "Tab",
            KeyEvent.KEYCODE_ESCAPE to "Escape",
            KeyEvent.KEYCODE_SPACE to "Space",
            KeyEvent.KEYCODE_MOVE_HOME to "Home",
            KeyEvent.KEYCODE_MOVE_END to "End",
            KeyEvent.KEYCODE_PAGE_UP to "PageUp",
            KeyEvent.KEYCODE_PAGE_DOWN to "PageDown",
        )

        expected.forEach { (keyCode, code) -> assertEquals(code, remoteKeyboardCode(keyCode)) }
    }

    @Test
    fun modifierKeysExposeTheirSide() {
        assertEquals("ShiftLeft", remoteKeyboardCode(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("ShiftRight", remoteKeyboardCode(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals("ControlLeft", remoteKeyboardCode(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("ControlRight", remoteKeyboardCode(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals("AltLeft", remoteKeyboardCode(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals("AltRight", remoteKeyboardCode(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals("MetaLeft", remoteKeyboardCode(KeyEvent.KEYCODE_META_LEFT))
        assertEquals("MetaRight", remoteKeyboardCode(KeyEvent.KEYCODE_META_RIGHT))
    }

    @Test
    fun stickyModifierNamesComeFromTheSameTable() {
        assertEquals("shift", remoteKeyModifier(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals("shift", remoteKeyModifier(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals("ctrl", remoteKeyModifier(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals("alt", remoteKeyModifier(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals("meta", remoteKeyModifier(KeyEvent.KEYCODE_META_LEFT))
        assertNull(remoteKeyModifier(KeyEvent.KEYCODE_A))
        assertNull(remoteKeyModifier(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun unmappedKeysReturnNullSoTheCallerDropsTheEvent() {
        assertNull(remoteKeyboardCode(KeyEvent.KEYCODE_UNKNOWN))
        assertNull(remoteKeyboardCode(KeyEvent.KEYCODE_VOLUME_UP))
        assertNull(remoteKeyboardCode(KeyEvent.KEYCODE_CAMERA))
    }

    @Test
    fun everyMappedCodeIsUniqueAndMatchesTheSchemaPattern() {
        val schemaPattern = Regex("^[A-Za-z0-9]{1,24}$")
        // KeyEvent.getMaxKeyCode() is a runtime call on the Android stub, so scan a literal range.
        val mapped = (0..MAX_SCANNED_KEY_CODE).mapNotNull { keyCode ->
            remoteKeyboardCode(keyCode)?.let { code -> keyCode to code }
        }

        assertTrue("Expected a substantial mapping table", mapped.size >= 90)
        mapped.forEach { (keyCode, code) ->
            assertTrue("Keycode $keyCode produced an invalid code: $code", schemaPattern.matches(code))
        }
        val duplicates = mapped.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        assertTrue("Duplicate W3C codes: $duplicates", duplicates.isEmpty())
    }

    private companion object {
        /** Above the highest documented `KEYCODE_*`; scanning past it just exercises nulls. */
        const val MAX_SCANNED_KEY_CODE = 300
    }
}
