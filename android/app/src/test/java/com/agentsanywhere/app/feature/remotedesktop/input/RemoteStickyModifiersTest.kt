package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "修饰键用粘滞方式：点一次 Ctrl 生效一次" (`docs/remote-desktop.md` section 8.2). One tap arms a
 * modifier for exactly the next `key` event, in the order the schema lists the `mods`.
 */
class RemoteStickyModifiersTest {

    private val modifiers = RemoteStickyModifiers()

    @Test
    fun armingAModifierConsumesItExactlyOnce() {
        assertEquals(listOf(RemoteModifierKey.Ctrl), modifiers.toggle(RemoteModifierKey.Ctrl))
        assertTrue(modifiers.isArmed(RemoteModifierKey.Ctrl))

        assertEquals(listOf("ctrl"), modifiers.consume())
        assertTrue("A sticky modifier is single use", modifiers.isEmpty)
        assertEquals(emptyList<String>(), modifiers.consume())
    }

    @Test
    fun tappingAnArmedModifierAgainDisarmsIt() {
        modifiers.toggle(RemoteModifierKey.Shift)

        assertEquals(emptyList<RemoteModifierKey>(), modifiers.toggle(RemoteModifierKey.Shift))
        assertFalse(modifiers.isArmed(RemoteModifierKey.Shift))
    }

    @Test
    fun modifiersKeepTheContractOrderNotTheTapOrder() {
        modifiers.toggle(RemoteModifierKey.Meta)
        modifiers.toggle(RemoteModifierKey.Ctrl)
        modifiers.toggle(RemoteModifierKey.Shift)

        assertEquals(listOf("ctrl", "shift", "meta"), modifiers.consume())
    }

    @Test
    fun armingASecondModifierKeepsTheFirstOne() {
        modifiers.toggle(RemoteModifierKey.Ctrl)
        modifiers.toggle(RemoteModifierKey.Alt)

        assertEquals(listOf(RemoteModifierKey.Ctrl, RemoteModifierKey.Alt), modifiers.active)
        assertEquals(listOf("ctrl", "alt"), modifiers.consume())
    }

    @Test
    fun theBarDropsTheOldestModifierWhenItIsFull() {
        modifiers.toggle(RemoteModifierKey.Ctrl)
        modifiers.toggle(RemoteModifierKey.Alt)
        modifiers.toggle(RemoteModifierKey.Shift)
        modifiers.toggle(RemoteModifierKey.Meta)

        assertEquals(listOf(RemoteModifierKey.Alt, RemoteModifierKey.Shift, RemoteModifierKey.Meta), modifiers.active)
    }

    @Test
    fun clearingDropsEverythingWithoutConsuming() {
        modifiers.toggle(RemoteModifierKey.Ctrl)
        modifiers.clear()

        assertTrue(modifiers.isEmpty)
        assertEquals(emptyList<String>(), modifiers.consume())
    }

    @Test
    fun oneShotKeysCarryTheirWireCode() {
        assertEquals("Escape", RemoteOneShotKey.Escape.wireCode)
        assertEquals("Tab", RemoteOneShotKey.Tab.wireCode)
    }
}
