package com.agentsanywhere.app.feature.remotedesktop.input

import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * Builds the JSON events a client publishes on the `aa.input` LiveKit data channel.
 * Wire facts are `contracts/remote-desktop/1.0/schemas/input-event.schema.json`; every
 * event carries the contract version `v = 1`.
 *
 * High-rate pointer motion does not belong here: it uses the 12-byte binary
 * `aa.pointer` frame from [encodePointerFrame] instead. The two `seq` counters are
 * independent (`docs/remote-desktop.md` section 7.6), so [nextSequence] is only a
 * convenience for this encoder.
 *
 * All sequences are taken modulo `2^32`, coordinates are clamped into the contract
 * ranges, and a `code` that the schema would reject is a programming error that throws
 * instead of producing an event the host must drop.
 */
class RemoteInputEncoder(
    private val nextSeq: () -> Long = defaultSequenceSource(),
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** The next `seq` for an `aa.input` event, already reduced to `0..4294967295`. */
    fun nextSequence(): Long = nextSeq() and U32_MASK

    /** `t: "pointer"`; `x`/`y` are normalized `0..1` and `b` is required for down/up. */
    fun pointerEvent(
        seq: Long,
        action: PointerAction,
        x: Float,
        y: Float,
        button: PointerButton? = null,
        display: Int = 0,
    ): JSONObject {
        val resolvedButton = button ?: if (action == PointerAction.Move) null else PointerButton.Left
        return baseEvent(seq, "pointer").apply {
            put("a", action.wireValue)
            put("x", x.clampToUnit().toDouble())
            put("y", y.clampToUnit().toDouble())
            resolvedButton?.let { put("b", it.wireValue) }
            if (action != PointerAction.Move || display != 0) put("d", display.coerceIn(0, MAX_DISPLAY_INDEX))
        }
    }

    /** `t: "wheel"`; [unit] is `pixel` or `line`, anything else falls back to `pixel`. */
    fun wheelEvent(
        seq: Long,
        dx: Float,
        dy: Float,
        unit: String = WHEEL_UNIT_PIXEL,
        display: Int = 0,
    ): JSONObject = baseEvent(seq, "wheel").apply {
        put("dx", dx.finiteOrZero().toDouble())
        put("dy", dy.finiteOrZero().toDouble())
        put("unit", if (unit == WHEEL_UNIT_LINE) WHEEL_UNIT_LINE else WHEEL_UNIT_PIXEL)
        put("d", display.coerceIn(0, MAX_DISPLAY_INDEX))
    }

    /**
     * `t: "key"` for a physical key. [code] must be a W3C `KeyboardEvent.code`
     * (`KeyA`, `Digit1`, `Escape`, `ControlLeft`); unknown modifiers in [mods] are dropped.
     */
    fun keyEvent(
        seq: Long,
        down: Boolean,
        code: String,
        mods: List<String> = emptyList(),
        repeat: Boolean = false,
    ): JSONObject {
        require(code.matches(KEY_CODE_PATTERN)) { "Not a W3C KeyboardEvent.code: $code" }
        return baseEvent(seq, "key").apply {
            put("a", if (down) "down" else "up")
            put("code", code)
            put("mods", JSONArray(mods.filter { it in KEY_MODIFIERS }.distinct()))
            put("repeat", repeat)
        }
    }

    /**
     * `t: "text"` with the IME-committed string. Text longer than
     * [REMOTE_INPUT_MAX_TEXT_CODE_POINTS] is truncated here; use [textEvents] when the
     * whole string must arrive (the host concatenates consecutive chunks).
     */
    fun textEvent(seq: Long, text: String): JSONObject = baseEvent(seq, "text").apply {
        put("s", truncateToCodePoints(text, REMOTE_INPUT_MAX_TEXT_CODE_POINTS))
    }

    /**
     * Splits [text] into `text` events of at most [REMOTE_INPUT_MAX_TEXT_CODE_POINTS]
     * characters each, never cutting a surrogate pair. Sequences are taken from
     * [startSeq] (first chunk) and [nextSeq] (the rest) unless [startSeq] is null.
     */
    fun textEvents(text: String, startSeq: Long? = null): List<JSONObject> {
        if (text.isEmpty()) return emptyList()
        var sequence = startSeq?.and(U32_MASK)
        return chunkByCodePoints(text, REMOTE_INPUT_MAX_TEXT_CODE_POINTS).map { chunk ->
            val seq = sequence ?: nextSequence()
            sequence = null
            textEvent(seq, chunk)
        }
    }

    /** `t: "ping"`; the only event without a `seq`, used to measure `aa.input` latency. */
    fun pingEvent(at: Long = clock()): JSONObject = JSONObject().apply {
        put("v", REMOTE_INPUT_EVENT_VERSION)
        put("t", "ping")
        put("at", at.coerceAtLeast(0L))
    }

    /**
     * `t: "release_all"`: the host releases every key and button it believes is held.
     * It is a synchronous state reset rather than ordered input, so it carries no `seq`.
     * [reason] is one of `blur`, `pagehide`, `handoff`, `manual`.
     */
    fun releaseAllEvent(reason: String): JSONObject {
        require(reason in RELEASE_ALL_REASONS) { "Unknown release_all reason: $reason" }
        return JSONObject().apply {
            put("v", REMOTE_INPUT_EVENT_VERSION)
            put("t", "release_all")
            put("reason", reason)
        }
    }

    private fun baseEvent(seq: Long, type: String): JSONObject = JSONObject().apply {
        put("v", REMOTE_INPUT_EVENT_VERSION)
        put("seq", seq and U32_MASK)
        put("ts", clock().coerceAtLeast(0L))
        put("t", type)
    }

    companion object {
        const val WHEEL_UNIT_PIXEL: String = "pixel"
        const val WHEEL_UNIT_LINE: String = "line"

        /** Base of the default monotonic counter; shared so every encoder starts at 0. */
        private fun defaultSequenceSource(): () -> Long {
            val counter = AtomicLong(0L)
            return { counter.getAndIncrement() }
        }
    }
}

/** Contract version of every `aa.input` event. */
const val REMOTE_INPUT_EVENT_VERSION: Int = 1

/** `input-event.schema.json` caps a single `text` payload at 4096 characters. */
const val REMOTE_INPUT_MAX_TEXT_CODE_POINTS: Int = 4096

/** `d` is a display index in `0..15`. */
const val MAX_DISPLAY_INDEX: Int = 15

private const val U32_MASK = 0xFFFFFFFFL

const val RELEASE_REASON_BLUR: String = "blur"
const val RELEASE_REASON_PAGE_HIDE: String = "pagehide"
const val RELEASE_REASON_HANDOFF: String = "handoff"
const val RELEASE_REASON_MANUAL: String = "manual"

private val RELEASE_ALL_REASONS = setOf(
    RELEASE_REASON_BLUR,
    RELEASE_REASON_PAGE_HIDE,
    RELEASE_REASON_HANDOFF,
    RELEASE_REASON_MANUAL,
)

private val KEY_MODIFIERS = setOf("ctrl", "alt", "shift", "meta")

/** `input-event.schema.json`: `code` matches `^[A-Za-z0-9]{1,24}$`. */
private val KEY_CODE_PATTERN = Regex("^[A-Za-z0-9]{1,24}$")

/** pointer `a`; `move` carries no button, `down`/`up` do. */
enum class PointerAction(val wireValue: String) {
    Move("move"),
    Down("down"),
    Up("up"),
}

/** pointer `b`, the button bit that changed. */
enum class PointerButton(val wireValue: String) {
    Left("left"),
    Right("right"),
    Middle("middle"),
}

private fun Float.clampToUnit(): Float = when {
    isNaN() -> 0f
    else -> coerceIn(0f, 1f)
}

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/** Truncates on code point boundaries so an emoji is never split in half. */
private fun truncateToCodePoints(text: String, maxCodePoints: Int): String {
    if (text.length <= maxCodePoints) return text
    var index = 0
    var counted = 0
    while (index < text.length && counted < maxCodePoints) {
        index += Character.charCount(text.codePointAt(index))
        counted += 1
    }
    return if (index >= text.length) text else text.substring(0, index)
}

private fun chunkByCodePoints(text: String, maxCodePoints: Int): List<String> {
    if (text.length <= maxCodePoints) return listOf(text)
    val chunks = mutableListOf<String>()
    var start = 0
    var index = 0
    var counted = 0
    while (index < text.length) {
        index += Character.charCount(text.codePointAt(index))
        counted += 1
        if (counted == maxCodePoints) {
            chunks += text.substring(start, index)
            start = index
            counted = 0
        }
    }
    if (start < text.length) chunks += text.substring(start)
    return chunks
}
