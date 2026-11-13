package com.agentsanywhere.app.feature.remotedesktop.input

/**
 * Modifier keys the floating bar offers (`docs/remote-desktop.md` section 8.1: "Ctrl / Alt /
 * Shift / Win / Esc / Tab 修饰键锁").
 *
 * A phone has no chord, so a modifier is *sticky*: one tap arms it for exactly the next
 * `key` or `text` event (section 8.2) and then it is cleared. [wireValue] is the schema's
 * `mods` value, which is also the LiveKit-free half of the mapping — the label is only what
 * the bar draws.
 */
enum class RemoteModifierKey(val wireValue: String, val label: String) {
    Ctrl("ctrl", "Ctrl"),
    Alt("alt", "Alt"),
    Shift("shift", "Shift"),
    Meta("meta", "Win"),
}

/**
 * Sticky modifier set of the viewer.
 *
 * Arms at most one of each modifier, keeps the contract's `mods` order stable and clears
 * the whole set when [consume] takes it, which is the "生效一次" rule of section 8.2.
 */
class RemoteStickyModifiers(
    private val maxArmed: Int = 3,
) {
    private val armed = linkedSetOf<RemoteModifierKey>()

    /** Modifiers armed for the next event, in contract order. */
    val active: List<RemoteModifierKey>
        get() = ORDER.filter { it in armed }

    val isEmpty: Boolean
        get() = armed.isEmpty()

    fun isArmed(modifier: RemoteModifierKey): Boolean = modifier in armed

    /**
     * Arms or disarms [modifier]. Returns the resulting armed set. Arming beyond
     * [maxArmed] drops the oldest one, because a bar of three is what a phone can carry.
     */
    fun toggle(modifier: RemoteModifierKey): List<RemoteModifierKey> {
        if (modifier in armed) {
            armed.remove(modifier)
            return active
        }
        armed.add(modifier)
        while (armed.size > maxArmed) {
            val oldest = armed.first()
            armed.remove(oldest)
        }
        return active
    }

    /** Takes the modifiers for one event and clears them: the sticky, single-use rule. */
    fun consume(): List<String> {
        val values = active.map { it.wireValue }
        armed.clear()
        return values
    }

    fun clear() {
        armed.clear()
    }

    private companion object {
        /** The order `input-event.schema.json` lists the `mods` values in. */
        val ORDER = listOf(
            RemoteModifierKey.Ctrl,
            RemoteModifierKey.Alt,
            RemoteModifierKey.Shift,
            RemoteModifierKey.Meta,
        )
    }
}

/** One-shot keys the bar sends immediately, with no sticky state. */
enum class RemoteOneShotKey(val wireCode: String, val label: String) {
    Escape("Escape", "Esc"),
    Tab("Tab", "Tab"),
}
