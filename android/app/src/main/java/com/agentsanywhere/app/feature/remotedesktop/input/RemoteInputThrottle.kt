package com.agentsanywhere.app.feature.remotedesktop.input

import java.util.ArrayDeque

/**
 * Input is rate limited to 120 events per second
 * (`docs/remote-desktop.md` section 7.6, `pointer-frame.md` section 5). A host that
 * receives more than that either throttles injection or lags behind the pointer, so the
 * limit belongs on the sending side.
 */
const val MAX_REMOTE_INPUT_EVENTS_PER_SECOND: Int = 120

/** Length of the sliding window the limit is measured over. */
const val REMOTE_INPUT_WINDOW_MILLIS: Long = 1_000L

/** What the caller is about to send; only pointer motion can be merged away. */
enum class RemoteInputKind(val coalescible: Boolean) {
    /** High-rate `aa.pointer` motion: the newest sample makes older ones redundant. */
    PointerMove(coalescible = true),

    /** Pointer `down`/`up` must never be reordered or merged with a move. */
    PointerButton(coalescible = false),
    Key(coalescible = false),
    Text(coalescible = false),
    Wheel(coalescible = false),
    Ping(coalescible = false),
    ReleaseAll(coalescible = false),
}

/** What [RemoteInputThrottle.offer] decided about one sample. */
enum class RemoteInputDecision {
    /** Inside the limit: send it now. */
    Send,

    /** Rate limited pointer motion; the newest coordinates are kept for the next free slot. */
    Coalesced,

    /** Rate limited discrete event; it is lost, but ordering of what was sent is intact. */
    Dropped,
}

/**
 * Sliding-window limiter for outgoing input, with pointer-move coalescing.
 *
 * The window is "the last [REMOTE_INPUT_WINDOW_MILLIS]" rather than a wall-clock second,
 * so a burst cannot straddle a second boundary and double the allowed rate. The clock is
 * injected, which is what makes the behaviour testable without sleeping.
 *
 * Coalescing keeps the stream honest: when a move is refused because the window is full,
 * [offer] returns [RemoteInputDecision.Coalesced] and flags that a newer position is
 * waiting, so the caller sends that position as soon as a slot frees up instead of
 * replaying every intermediate sample. Discrete events (buttons, keys, text, wheel) are
 * never merged: they either go out in order or are [RemoteInputDecision.Dropped].
 */
class RemoteInputThrottle(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val maxEventsPerSecond: Int = MAX_REMOTE_INPUT_EVENTS_PER_SECOND,
) {
    private val accepted = ArrayDeque<Long>()
    private var coalescedMovePending = false

    /** True when a rate-limited pointer move is waiting to be replaced by the newest one. */
    val hasCoalescedMove: Boolean
        get() = coalescedMovePending

    /** Number of events accepted inside the current window; diagnostics and tests. */
    val eventsInWindow: Int
        get() = accepted.size

    /** Takes one slot. Returns false when the window already holds [maxEventsPerSecond]. */
    fun tryAcquire(nowMillis: Long = clock()): Boolean {
        prune(nowMillis)
        if (accepted.size >= maxEventsPerSecond) return false
        accepted.addLast(nowMillis)
        return true
    }

    /** Offers one sample and reports what to do with it. */
    fun offer(kind: RemoteInputKind, nowMillis: Long = clock()): RemoteInputDecision {
        if (tryAcquire(nowMillis)) return RemoteInputDecision.Send
        if (!kind.coalescible) return RemoteInputDecision.Dropped
        coalescedMovePending = true
        return RemoteInputDecision.Coalesced
    }

    /**
     * Consumes the pending coalesced move: the caller then sends the newest pointer
     * position (if it still has one) through [tryAcquire].
     */
    fun takeCoalescedMove(): Boolean {
        val pending = coalescedMovePending
        coalescedMovePending = false
        return pending
    }

    /** Clears the window, for example when input pauses while the app is in the background. */
    fun reset() {
        accepted.clear()
        coalescedMovePending = false
    }

    private fun prune(nowMillis: Long) {
        while (accepted.isNotEmpty() && nowMillis - accepted.first() >= REMOTE_INPUT_WINDOW_MILLIS) {
            accepted.removeFirst()
        }
    }
}
