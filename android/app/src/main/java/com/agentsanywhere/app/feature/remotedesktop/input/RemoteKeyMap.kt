package com.agentsanywhere.app.feature.remotedesktop.input

import android.view.KeyEvent

/**
 * Android `KeyEvent.keyCode` to W3C UI Events `KeyboardEvent.code`.
 *
 * The remote host is a browser-engine input target, so only the physical key identity is
 * sent: [`RemoteInputEncoder.keyEvent`] carries `code` plus the sticky modifier names,
 * never a character (that is the `text` event's job). A code is only emitted when it
 * matches the schema's `^[A-Za-z0-9]{1,24}$`, so unmapped keys — media keys, hardware
 * volume, Android's `KEYCODE_UNKNOWN` — return null and the caller drops the event.
 *
 * The table uses `KeyEvent.KEYCODE_*` constants, which the compiler inlines, so it can be
 * unit tested as plain Kotlin without creating `KeyEvent` instances.
 */
fun remoteKeyboardCode(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_A -> "KeyA"
    KeyEvent.KEYCODE_B -> "KeyB"
    KeyEvent.KEYCODE_C -> "KeyC"
    KeyEvent.KEYCODE_D -> "KeyD"
    KeyEvent.KEYCODE_E -> "KeyE"
    KeyEvent.KEYCODE_F -> "KeyF"
    KeyEvent.KEYCODE_G -> "KeyG"
    KeyEvent.KEYCODE_H -> "KeyH"
    KeyEvent.KEYCODE_I -> "KeyI"
    KeyEvent.KEYCODE_J -> "KeyJ"
    KeyEvent.KEYCODE_K -> "KeyK"
    KeyEvent.KEYCODE_L -> "KeyL"
    KeyEvent.KEYCODE_M -> "KeyM"
    KeyEvent.KEYCODE_N -> "KeyN"
    KeyEvent.KEYCODE_O -> "KeyO"
    KeyEvent.KEYCODE_P -> "KeyP"
    KeyEvent.KEYCODE_Q -> "KeyQ"
    KeyEvent.KEYCODE_R -> "KeyR"
    KeyEvent.KEYCODE_S -> "KeyS"
    KeyEvent.KEYCODE_T -> "KeyT"
    KeyEvent.KEYCODE_U -> "KeyU"
    KeyEvent.KEYCODE_V -> "KeyV"
    KeyEvent.KEYCODE_W -> "KeyW"
    KeyEvent.KEYCODE_X -> "KeyX"
    KeyEvent.KEYCODE_Y -> "KeyY"
    KeyEvent.KEYCODE_Z -> "KeyZ"

    KeyEvent.KEYCODE_0 -> "Digit0"
    KeyEvent.KEYCODE_1 -> "Digit1"
    KeyEvent.KEYCODE_2 -> "Digit2"
    KeyEvent.KEYCODE_3 -> "Digit3"
    KeyEvent.KEYCODE_4 -> "Digit4"
    KeyEvent.KEYCODE_5 -> "Digit5"
    KeyEvent.KEYCODE_6 -> "Digit6"
    KeyEvent.KEYCODE_7 -> "Digit7"
    KeyEvent.KEYCODE_8 -> "Digit8"
    KeyEvent.KEYCODE_9 -> "Digit9"

    KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
    KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
    KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"

    KeyEvent.KEYCODE_ENTER -> "Enter"
    KeyEvent.KEYCODE_NUMPAD_ENTER -> "NumpadEnter"
    KeyEvent.KEYCODE_DEL -> "Backspace"
    KeyEvent.KEYCODE_FORWARD_DEL -> "Delete"
    KeyEvent.KEYCODE_TAB -> "Tab"
    KeyEvent.KEYCODE_ESCAPE -> "Escape"
    KeyEvent.KEYCODE_SPACE -> "Space"
    KeyEvent.KEYCODE_MOVE_HOME -> "Home"
    KeyEvent.KEYCODE_MOVE_END -> "End"
    KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
    KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
    KeyEvent.KEYCODE_INSERT -> "Insert"

    KeyEvent.KEYCODE_SHIFT_LEFT -> "ShiftLeft"
    KeyEvent.KEYCODE_SHIFT_RIGHT -> "ShiftRight"
    KeyEvent.KEYCODE_CTRL_LEFT -> "ControlLeft"
    KeyEvent.KEYCODE_CTRL_RIGHT -> "ControlRight"
    KeyEvent.KEYCODE_ALT_LEFT -> "AltLeft"
    KeyEvent.KEYCODE_ALT_RIGHT -> "AltRight"
    KeyEvent.KEYCODE_META_LEFT -> "MetaLeft"
    KeyEvent.KEYCODE_META_RIGHT -> "MetaRight"
    KeyEvent.KEYCODE_CAPS_LOCK -> "CapsLock"
    KeyEvent.KEYCODE_NUM_LOCK -> "NumLock"
    KeyEvent.KEYCODE_SCROLL_LOCK -> "ScrollLock"

    KeyEvent.KEYCODE_GRAVE -> "Backquote"
    KeyEvent.KEYCODE_MINUS -> "Minus"
    KeyEvent.KEYCODE_EQUALS -> "Equal"
    KeyEvent.KEYCODE_LEFT_BRACKET -> "BracketLeft"
    KeyEvent.KEYCODE_RIGHT_BRACKET -> "BracketRight"
    KeyEvent.KEYCODE_BACKSLASH -> "Backslash"
    KeyEvent.KEYCODE_SEMICOLON -> "Semicolon"
    KeyEvent.KEYCODE_APOSTROPHE -> "Quote"
    KeyEvent.KEYCODE_COMMA -> "Comma"
    KeyEvent.KEYCODE_PERIOD -> "Period"
    KeyEvent.KEYCODE_SLASH -> "Slash"

    KeyEvent.KEYCODE_NUMPAD_0 -> "Numpad0"
    KeyEvent.KEYCODE_NUMPAD_1 -> "Numpad1"
    KeyEvent.KEYCODE_NUMPAD_2 -> "Numpad2"
    KeyEvent.KEYCODE_NUMPAD_3 -> "Numpad3"
    KeyEvent.KEYCODE_NUMPAD_4 -> "Numpad4"
    KeyEvent.KEYCODE_NUMPAD_5 -> "Numpad5"
    KeyEvent.KEYCODE_NUMPAD_6 -> "Numpad6"
    KeyEvent.KEYCODE_NUMPAD_7 -> "Numpad7"
    KeyEvent.KEYCODE_NUMPAD_8 -> "Numpad8"
    KeyEvent.KEYCODE_NUMPAD_9 -> "Numpad9"
    KeyEvent.KEYCODE_NUMPAD_DIVIDE -> "NumpadDivide"
    KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> "NumpadMultiply"
    KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> "NumpadSubtract"
    KeyEvent.KEYCODE_NUMPAD_ADD -> "NumpadAdd"
    KeyEvent.KEYCODE_NUMPAD_DOT -> "NumpadDecimal"
    KeyEvent.KEYCODE_NUMPAD_COMMA -> "NumpadComma"

    KeyEvent.KEYCODE_F1 -> "F1"
    KeyEvent.KEYCODE_F2 -> "F2"
    KeyEvent.KEYCODE_F3 -> "F3"
    KeyEvent.KEYCODE_F4 -> "F4"
    KeyEvent.KEYCODE_F5 -> "F5"
    KeyEvent.KEYCODE_F6 -> "F6"
    KeyEvent.KEYCODE_F7 -> "F7"
    KeyEvent.KEYCODE_F8 -> "F8"
    KeyEvent.KEYCODE_F9 -> "F9"
    KeyEvent.KEYCODE_F10 -> "F10"
    KeyEvent.KEYCODE_F11 -> "F11"
    KeyEvent.KEYCODE_F12 -> "F12"

    else -> null
}

/**
 * Modifier name (`ctrl`/`alt`/`shift`/`meta`, the schema's `mods` values) for a modifier
 * key press, or null when [keyCode] is not a modifier. The UI keeps modifiers sticky:
 * one tap arms the modifier for the next `key` event.
 */
fun remoteKeyModifier(keyCode: Int): String? = when (keyCode) {
    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl"
    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> "alt"
    KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> "meta"
    else -> null
}
