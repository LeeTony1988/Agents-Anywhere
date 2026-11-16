package com.agentsanywhere.app.feature.remotedesktop.input

import kotlin.math.hypot

/** Default pointer gain; one view pixel of finger travel moves the pointer 1.8 remote pixels. */
const val DEFAULT_POINTER_SENSITIVITY: Float = 1.8f

/** Serial number of an `aa.pointer` frame is the sender's own counter and is 32 bits wide. */
private const val U32_MASK = 0xFFFFFFFFL

/**
 * Turns gestures, keys and IME text into the two input channels of a session.
 *
 * Everything that needs the view geometry arrives as a [RemoteViewport], so the whole class
 * is pure Kotlin: the cursor lives in normalized `0..1` space, taps are mapped through
 * [normalizeTouch] (the inverse of the local pan/zoom, which is the "点不准" trap of
 * `docs/remote-desktop.md` section 8.1), and pointer frames carry [toWireCoordinates].
 *
 * High-rate motion goes out as 12-byte `aa.pointer` frames and everything else as JSON on
 * `aa.input`; button transitions also publish a pointer frame so the lossy plane cannot
 * resurrect a held button. Both planes pass through the 120 events/second
 * [RemoteInputThrottle].
 */
class RemoteInputDispatcher(
    private val sink: RemoteInputSink,
    private val encoder: RemoteInputEncoder = RemoteInputEncoder(),
    private val throttle: RemoteInputThrottle = RemoteInputThrottle(),
    val modifiers: RemoteStickyModifiers = RemoteStickyModifiers(),
    private val sensitivity: Float = DEFAULT_POINTER_SENSITIVITY,
    /**
     * One-line diagnostics for "the picture works but nothing answers". Injected instead of
     * calling `android.util.Log` so this class stays pure Kotlin and its unit tests keep
     * running on a plain JVM; the viewer wires `android.util.Log`, tests leave it silent.
     */
    private val log: (String) -> Unit = {},
) {
    /** Pointer position in the remote desktop's normalized `0..1` space. */
    var cursorX: Float = 0.5f
        private set

    var cursorY: Float = 0.5f
        private set

    /** Button bitmask the host currently believes is held (`aa.pointer` `buttons`). */
    var buttons: Int = 0
        private set

    /** Direct keeps the old fixed gain; Mouse uses [RemotePointerGain]. */
    var pointerMode: RemotePointerMode = RemotePointerMode.Direct
        private set

    private var lastButtons = 0
    private var pointerSequence = 0L

    /**
     * Handles the remote half of [actions] and returns the local ones the viewer still has
     * to apply (zoom, pan, drag-lock indicator, keyboard).
     */
    fun handle(
        actions: List<RemoteGestureAction>,
        viewport: RemoteViewport,
        speedGain: Boolean = false,
    ): List<RemoteGestureAction> {
        // Pointer motion is a 120 Hz hot path; logging every sample would put string
        // building and logcat writes on the main thread and work against "跟手".
        // Keep diagnostics for buttons, taps, scrolling and local view changes only.
        if (actions.any { it !is RemoteGestureAction.RemotePointer }) {
            log(
                "gestures=" + actions.joinToString(",") { it.javaClass.simpleName } +
                    " viewport=${viewport.safeWidth}x${viewport.safeHeight}" +
                    " scale=${viewport.safeScale}",
            )
        }
        val local = mutableListOf<RemoteGestureAction>()
        actions.forEach { action ->
            when (action) {
                is RemoteGestureAction.LocalZoom,
                is RemoteGestureAction.LocalPan,
                is RemoteGestureAction.DragLock,
                RemoteGestureAction.ShowKeyboard,
                -> local += action

                is RemoteGestureAction.RemotePointer ->
                    movePointer(action.dx, action.dy, viewport, action.elapsedMillis, speedGain)

                is RemoteGestureAction.RemoteClick ->
                    clickAt(action.x, action.y, action.button, viewport)

                is RemoteGestureAction.ClickAtCursor ->
                    clickHere(action.button)

                is RemoteGestureAction.RemotePointerDown ->
                    pressAt(action.x, action.y, action.button, viewport)

                is RemoteGestureAction.RightClick ->
                    clickAt(action.x, action.y, PointerButton.Right, viewport)

                RemoteGestureAction.RightClickAtCursor ->
                    clickHere(PointerButton.Right)

                is RemoteGestureAction.Scroll -> scroll(action.dx, action.dy)

                is RemoteGestureAction.PointerDown -> setButton(action.button, down = true)

                is RemoteGestureAction.PointerUp -> {
                    // The last motion may have been rate-limited; land it before release
                    // so the drag ends where the finger lifted, not one sample earlier.
                    flushCoalescedMove()
                    setButton(action.button, down = false)
                }

                RemoteGestureAction.ReleaseAll -> releaseAll(RELEASE_REASON_MANUAL)
            }
        }
        return local
    }

    /** Switches the one-finger mapping without touching the wire button mask. */
    fun setPointerMode(mode: RemotePointerMode) {
        pointerMode = mode
    }

    /**
     * Moves the remote pointer by a view-pixel delta and sends one `aa.pointer` frame.
     *
     * Direct mode keeps the historical fixed [sensitivity]. Mouse mode uses a
     * speed-dependent gain so a slow finger can land on a 16 px target while a fast
     * swipe still crosses the desktop.
     */
    fun movePointer(
        dxView: Float,
        dyView: Float,
        viewport: RemoteViewport,
        elapsedMillis: Long = 16L,
        speedGain: Boolean = pointerMode == RemotePointerMode.Mouse,
    ) {
        val contentWidth = viewport.safeWidth * viewport.safeScale
        val contentHeight = viewport.safeHeight * viewport.safeScale
        if (contentWidth <= 0f || contentHeight <= 0f) return
        val gain = if (speedGain) {
            RemotePointerGain.gain(hypot(dxView, dyView), elapsedMillis)
        } else {
            sensitivity
        }
        val dx = dxView / contentWidth * gain
        val dy = dyView / contentHeight * gain
        setCursor(cursorX + dx, cursorY + dy)
        sendPointerMove()
    }

    /** Moves the pointer to a normalized position without pressing anything. */
    fun setCursor(x: Float, y: Float) {
        cursorX = x.clampToUnit()
        cursorY = y.clampToUnit()
    }

    /** Presses and releases [button] at the view point `(x, y)`, moving the pointer there first. */
    fun clickAt(x: Float, y: Float, button: PointerButton, viewport: RemoteViewport) {
        val (normalizedX, normalizedY) = normalizeTouch(x, y, viewport)
        setCursor(normalizedX, normalizedY)
        pointerButtonEvent(down = true, button = button, force = true)
        pointerButtonEvent(down = false, button = button, force = true)
    }

    /** Clicks [button] at the current cursor, without moving it (trackpad mode). */
    fun clickHere(button: PointerButton) {
        pointerButtonEvent(down = true, button = button, force = true)
        pointerButtonEvent(down = false, button = button, force = true)
    }

    /**
     * Moves the pointer to `(x, y)` and presses [button]. The first move of a
     * single-finger drag uses this so the press lands where the finger went down, not at
     * the previous cursor position.
     */
    fun pressAt(x: Float, y: Float, button: PointerButton, viewport: RemoteViewport) {
        val (normalizedX, normalizedY) = normalizeTouch(x, y, viewport)
        setCursor(normalizedX, normalizedY)
        setButton(button, down = true)
    }

    /** Sends `pointer` `down`/`up` at the current cursor; used by the drag lock. */
    fun setButton(button: PointerButton, down: Boolean) {
        val bit = button.bit
        buttons = if (down) buttons or bit else buttons and bit.inv()
        pointerButtonEvent(down = down, button = button, force = true)
        // Keep the lossy pointer plane in step with the reliable button event. A move
        // frame sent just before the release can otherwise arrive after it on the
        // unordered channel and resurrect the held button; this frame carries the new
        // bitmask and a newer sequence number, so the host ignores that stale one.
        // Button transitions are critical and rare, so they bypass the motion budget;
        // losing a release would leave the host's left button stuck down.
        sendPointerMove(force = true)
    }

    /**
     * `t: "wheel"` in pixel units; positive [dy] scrolls the remote desktop down.
     * Returns false when the 120/s budget dropped the event, so a caller dragging a
     * rail can keep the accumulated distance for the next slot instead of losing it.
     */
    fun scroll(dx: Float, dy: Float): Boolean {
        if (dx == 0f && dy == 0f) return true
        if (throttle.offer(RemoteInputKind.Wheel) != RemoteInputDecision.Send) return false
        sink.sendInput(encoder.wheelEvent(encoder.nextSequence(), dx = dx, dy = dy).toString())
        return true
    }

    /**
     * One IME commit; long text is split so every `text` event stays inside the schema.
     *
     * `text` has no `mods` field (`input-event.schema.json` is `additionalProperties: false`),
     * so sticky modifiers stay armed for the next `key` event instead of being consumed here.
     */
    fun sendText(text: String) {
        if (text.isEmpty()) return
        log("text=${text.length} chars")
        encoder.textEvents(text).forEach { event ->
            if (throttle.offer(RemoteInputKind.Text) != RemoteInputDecision.Send) return
            sink.sendInput(event.toString())
        }
    }

    /**
     * One physical key, with the sticky modifiers of the bar attached and cleared. [code] is
     * a W3C `KeyboardEvent.code`; keys the map does not know are dropped by the caller.
     */
    fun sendKey(code: String, down: Boolean, repeat: Boolean = false) {
        if (throttle.offer(RemoteInputKind.Key) != RemoteInputDecision.Send) {
            log("key $code down=$down dropped by the rate limiter")
            return
        }
        val mods = modifiers.consume()
        log("key $code down=$down repeat=$repeat mods=$mods")
        sink.sendInput(encoder.keyEvent(encoder.nextSequence(), down = down, code = code, mods = mods, repeat = repeat).toString())
    }

    /** A bar key such as Esc or Tab is a full press/release pair in one tap. */
    fun sendOneShot(code: String) {
        sendKey(code, down = true)
        sendKey(code, down = false)
    }

    /** Drops every held key and button; also clears the local button mask. */
    fun releaseAll(reason: String) {
        val hadButtons = buttons != 0
        buttons = 0
        // release_all is a safety event: throttling it away is worse than one extra
        // message, especially when the app is pausing mid-drag.
        if (hadButtons) sink.sendInput(encoder.releaseAllEvent(reason).toString())
        // Same stale-frame guard as setButton: publish a frame whose bitmask is empty so
        // a late move cannot leave the host believing the button is still held. This
        // also advances the frame sequence, fencing older in-flight drag frames.
        sendPointerMove(force = true)
    }

    /** Sends a bare `ping` so the viewer can measure `aa.input` latency. */
    fun ping(at: Long = System.currentTimeMillis()) {
        if (throttle.offer(RemoteInputKind.Ping) != RemoteInputDecision.Send) return
        sink.sendInput(encoder.pingEvent(at).toString())
    }

    /**
     * Re-sends the current position when the rate limiter coalesced a move away, so the host
     * ends on the newest sample instead of the last one that fit inside the window.
     */
    fun flushCoalescedMove() {
        if (!throttle.takeCoalescedMove()) return
        sendPointerMove()
    }

    private fun pointerButtonEvent(down: Boolean, button: PointerButton, force: Boolean = false) {
        log(
            "button ${button.wireValue} down=$down at (${"%.3f".format(cursorX)}, ${"%.3f".format(cursorY)})",
        )
        if (!force && throttle.offer(RemoteInputKind.PointerButton) != RemoteInputDecision.Send) return
        sink.sendInput(
            encoder.pointerEvent(
                seq = encoder.nextSequence(),
                action = if (down) PointerAction.Down else PointerAction.Up,
                x = cursorX,
                y = cursorY,
                button = button,
            ).toString(),
        )
    }

    private fun sendPointerMove(force: Boolean = false) {
        if (!force && throttle.offer(RemoteInputKind.PointerMove) != RemoteInputDecision.Send) return
        val (x, y) = toWireCoordinates(cursorX to cursorY)
        val flags = if (buttons != lastButtons) REMOTE_POINTER_FLAG_BUTTONS_CHANGED else 0
        lastButtons = buttons
        sink.sendPointerFrame(
            encodePointerFrame(
                x = x,
                y = y,
                buttons = buttons,
                flags = flags,
                seq = pointerSequence++ and U32_MASK,
            ),
        )
    }
}

private val PointerButton.bit: Int
    get() = when (this) {
        PointerButton.Left -> REMOTE_POINTER_BUTTON_LEFT
        PointerButton.Right -> REMOTE_POINTER_BUTTON_RIGHT
        PointerButton.Middle -> REMOTE_POINTER_BUTTON_MIDDLE
    }

private fun Float.clampToUnit(): Float = when {
    isNaN() -> 0f
    else -> coerceIn(0f, 1f)
}

/** The two data channels the input pipeline needs; [MediaSession] is the production one. */
interface RemoteInputSink {
    /** Publishes one `aa.input` JSON event on the reliable, ordered data channel. */
    fun sendInput(json: String)

    /** Publishes one 12-byte `aa.pointer` frame on the lossy, unordered data channel. */
    fun sendPointerFrame(bytes: ByteArray)
}
