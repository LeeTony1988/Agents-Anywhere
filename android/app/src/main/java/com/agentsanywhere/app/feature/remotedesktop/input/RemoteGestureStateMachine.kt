package com.agentsanywhere.app.feature.remotedesktop.input

import kotlin.math.abs

/**
 * Pure gesture recognizer for the remote desktop viewer (`docs/remote-desktop.md` section
 * 8.1).
 *
 * Compose delivers raw pointer events; this class turns them into [RemoteGestureAction]s
 * without knowing about Compose, a viewport or a wire format. Everything that needs a view
 * size (mapping a point to the remote desktop, applying local zoom/pan) is the caller's
 * job, which is what lets the whole table below be unit tested by feeding coordinates and
 * timestamps:
 *
 * | gesture | action |
 * | --- | --- |
 * | single-finger tap | [RemoteGestureAction.RemoteClick] (left button) |
 * | single-finger drag | [RemoteGestureAction.RemotePointer] (relative mode) |
 * | long press 500 ms | [RemoteGestureAction.RightClick] |
 * | two-finger vertical swipe | [RemoteGestureAction.Scroll] while the picture fits, [RemoteGestureAction.LocalPan] once it is zoomed |
 * | two-finger pinch | [RemoteGestureAction.LocalZoom] |
 * | double tap | [RemoteGestureAction.DragLock] plus a held left button |
 * | three-finger swipe down | [RemoteGestureAction.ShowKeyboard] |
 *
 * Call [down] when a gesture starts, [move] for every pointer event that has at least one
 * pressed pointer, [longPress] from the caller's 500 ms timer and [up] when the last finger
 * lifts. A gesture that is interrupted (a call, the screen locking) goes through [cancel].
 *
 * A double tap is recognized from the timestamps of two taps: the first tap already sent a
 * click, because delaying every click by the double-tap window would make the viewer feel
 * broken. The second tap therefore toggles the drag lock instead of clicking again.
 */
class RemoteGestureStateMachine(
    private val config: RemoteGestureConfig = RemoteGestureConfig(),
    initiallyZoomed: Boolean = false,
) {
    private enum class Phase { Idle, Single, Two, Three }

    private enum class TwoFingerMode { Undecided, Zoom, Scroll, Pan }

    private var phase = Phase.Idle
    private var pointerCount = 0

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpread = 0f

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var movedBeyondSlop = false
    private var longPressFired = false

    private var twoFingerMode = TwoFingerMode.Undecided
    private var accumulated = 0f
    private var keyboardShown = false

    private var dragLocked = false
    private var lastTapAt: Long? = null
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var zoomed = initiallyZoomed

    /** True after a double tap armed the drag lock and the left button stays pressed. */
    val isDragLocked: Boolean
        get() = dragLocked

    /** True while a gesture is in progress (a finger is down). */
    val isGestureActive: Boolean
        get() = phase != Phase.Idle

    /** True once the current gesture travelled beyond the tap slop. */
    val hasMoved: Boolean
        get() = movedBeyondSlop

    /** How long a still finger has to stay down before the caller's timer fires [longPress]. */
    val longPressMillis: Long
        get() = config.longPressMillis

    /**
     * Tells the machine whether the picture is magnified locally. A two-finger drag scrolls
     * the remote desktop while the whole frame fits; once the user zoomed in, the same drag
     * pans the picture instead, so it never fights with the scroll gesture.
     */
    fun setZoomed(zoomed: Boolean) {
        this.zoomed = zoomed
    }

    /** Starts a gesture; [spread] is the distance between two fingers (0 for one finger). */
    fun down(
        x: Float,
        y: Float,
        timeMillis: Long,
        pointerCount: Int = 1,
        spread: Float = 0f,
    ): List<RemoteGestureAction> {
        this.pointerCount = pointerCount.coerceAtLeast(1)
        phase = when (this.pointerCount) {
            1 -> Phase.Single
            2 -> Phase.Two
            else -> Phase.Three
        }
        downX = x
        downY = y
        lastX = x
        lastY = y
        lastSpread = spread
        pendingDx = 0f
        pendingDy = 0f
        movedBeyondSlop = false
        longPressFired = false
        twoFingerMode = TwoFingerMode.Undecided
        accumulated = 0f
        keyboardShown = false
        return emptyList()
    }

    /** One pointer event with [pointerCount] pressed pointers. */
    fun move(
        x: Float,
        y: Float,
        timeMillis: Long,
        pointerCount: Int = this.pointerCount,
        spread: Float = 0f,
    ): List<RemoteGestureAction> {
        if (phase == Phase.Idle) return emptyList()
        val resolvedCount = pointerCount.coerceAtLeast(1)
        if (resolvedCount != this.pointerCount) {
            reanchor(x, y, resolvedCount, spread)
        }
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        if (!movedBeyondSlop && (abs(x - downX) > config.tapSlopPx || abs(y - downY) > config.tapSlopPx)) {
            movedBeyondSlop = true
        }
        return when (phase) {
            Phase.Single -> singleMove(dx, dy)
            Phase.Two -> twoMove(dx, dy, x, y, spread)
            Phase.Three -> threeMove(dy)
            Phase.Idle -> emptyList()
        }
    }

    /** Ends the gesture; a short, still press becomes a tap, a double tap toggles the lock. */
    fun up(
        x: Float,
        y: Float,
        timeMillis: Long,
        pointerCount: Int = this.pointerCount,
        spread: Float = 0f,
    ): List<RemoteGestureAction> {
        if (phase == Phase.Idle) return emptyList()
        val actions = when (phase) {
            Phase.Single -> if (movedBeyondSlop || longPressFired) {
                emptyList()
            } else {
                tapActions(x, y, timeMillis)
            }

            Phase.Two -> if (twoFingerMode == TwoFingerMode.Undecided && !movedBeyondSlop) {
                // A two-finger tap is the gesture for a right click, the mouse counterpart
                // of the long press that needs a still finger.
                listOf(RemoteGestureAction.RightClick(x, y))
            } else {
                emptyList()
            }

            else -> emptyList()
        }
        reset()
        return actions
    }

    /** The caller's 500 ms long-press timer expired without the finger moving. */
    fun longPress(timeMillis: Long): List<RemoteGestureAction> {
        if (phase != Phase.Single || movedBeyondSlop || longPressFired) return emptyList()
        longPressFired = true
        return listOf(RemoteGestureAction.RightClick(downX, downY))
    }

    /**
     * Explicit double-tap entry, for a host that detects the double tap itself (Compose's
     * `detectTapGestures(onDoubleTap = ...)`). The gesture state machine normally derives it
     * from [up] timestamps.
     */
    fun doubleTap(): List<RemoteGestureAction> = toggleDragLock()

    /** The gesture was interrupted: release a held button so the host does not stick. */
    fun cancel(): List<RemoteGestureAction> {
        val actions = if (dragLocked) {
            dragLocked = false
            listOf(RemoteGestureAction.ReleaseAll)
        } else {
            emptyList()
        }
        reset()
        return actions
    }

    // ---- phases ------------------------------------------------------------

    private fun singleMove(dx: Float, dy: Float): List<RemoteGestureAction> {
        if (longPressFired) return emptyList()
        if (!movedBeyondSlop) {
            // Keep the sub-slop motion: once the drag starts, the pointer must not lose the
            // first few pixels the finger travelled.
            pendingDx += dx
            pendingDy += dy
            return emptyList()
        }
        val totalDx = pendingDx + dx
        val totalDy = pendingDy + dy
        pendingDx = 0f
        pendingDy = 0f
        if (totalDx == 0f && totalDy == 0f) return emptyList()
        return listOf(RemoteGestureAction.RemotePointer(totalDx, totalDy))
    }

    private fun twoMove(dx: Float, dy: Float, x: Float, y: Float, spread: Float): List<RemoteGestureAction> {
        if (lastSpread > 0f && spread > 0f) {
            val relative = abs(spread - lastSpread) / lastSpread
            if (twoFingerMode == TwoFingerMode.Undecided && relative >= config.pinchThreshold) {
                twoFingerMode = TwoFingerMode.Zoom
            }
        }
        val actions = when (twoFingerMode) {
            TwoFingerMode.Zoom -> {
                val factor = if (lastSpread > 0f && spread > 0f) spread / lastSpread else 1f
                if (factor != 1f) {
                    listOf(RemoteGestureAction.LocalZoom(factor = factor, focusX = x, focusY = y))
                } else {
                    emptyList()
                }
            }

            TwoFingerMode.Scroll -> listOf(RemoteGestureAction.Scroll(dx = dx, dy = dy))
            TwoFingerMode.Pan -> listOf(RemoteGestureAction.LocalPan(dx = dx, dy = dy))
            TwoFingerMode.Undecided -> {
                accumulated += dy
                if (abs(accumulated) >= config.twoFingerSlopPx) {
                    if (zoomed) {
                        twoFingerMode = TwoFingerMode.Pan
                        listOf(RemoteGestureAction.LocalPan(dx = dx, dy = accumulated))
                    } else {
                        twoFingerMode = TwoFingerMode.Scroll
                        listOf(RemoteGestureAction.Scroll(dx = dx, dy = accumulated))
                    }
                } else {
                    emptyList()
                }
            }
        }
        lastSpread = spread
        return actions
    }

    private fun threeMove(dy: Float): List<RemoteGestureAction> {
        if (keyboardShown) return emptyList()
        accumulated += dy
        if (accumulated >= config.threeFingerSwipePx) {
            keyboardShown = true
            return listOf(RemoteGestureAction.ShowKeyboard)
        }
        return emptyList()
    }

    private fun tapActions(x: Float, y: Float, timeMillis: Long): List<RemoteGestureAction> {
        val previousTap = lastTapAt
        val doubleTap = previousTap != null &&
            timeMillis - previousTap <= config.doubleTapMillis &&
            abs(x - lastTapX) <= config.tapSlopPx &&
            abs(y - lastTapY) <= config.tapSlopPx
        if (doubleTap) {
            lastTapAt = null
            return toggleDragLock()
        }
        lastTapAt = timeMillis
        lastTapX = x
        lastTapY = y
        return listOf(RemoteGestureAction.RemoteClick(PointerButton.Left, x, y))
    }

    private fun toggleDragLock(): List<RemoteGestureAction> {
        dragLocked = !dragLocked
        return if (dragLocked) {
            listOf(
                RemoteGestureAction.PointerDown(PointerButton.Left),
                RemoteGestureAction.DragLock(locked = true),
            )
        } else {
            listOf(
                RemoteGestureAction.PointerUp(PointerButton.Left),
                RemoteGestureAction.DragLock(locked = false),
            )
        }
    }

    private fun reanchor(x: Float, y: Float, count: Int, spread: Float) {
        pointerCount = count
        phase = when (count) {
            1 -> Phase.Single
            2 -> Phase.Two
            else -> Phase.Three
        }
        lastX = x
        lastY = y
        lastSpread = spread
        twoFingerMode = TwoFingerMode.Undecided
        accumulated = 0f
        pendingDx = 0f
        pendingDy = 0f
        // A finger added mid-drag keeps the drag's "moved" state so the release is not a tap.
        keyboardShown = false
    }

    private fun reset() {
        phase = Phase.Idle
        pointerCount = 0
        pendingDx = 0f
        pendingDy = 0f
        movedBeyondSlop = false
        longPressFired = false
        twoFingerMode = TwoFingerMode.Undecided
        accumulated = 0f
        keyboardShown = false
        lastSpread = 0f
        downX = 0f
        downY = 0f
    }
}

/** Gesture thresholds of the viewer; the defaults follow `docs/remote-desktop.md` section 8.1. */
data class RemoteGestureConfig(
    /** A still finger becomes a right click after this long. */
    val longPressMillis: Long = 500L,
    /** Two taps closer than this are a double tap (drag lock). */
    val doubleTapMillis: Long = 300L,
    /** Motion below this is still a tap; above it the gesture is a pointer drag. */
    val tapSlopPx: Float = 16f,
    /** Vertical travel before a two-finger drag becomes a scroll/pan. */
    val twoFingerSlopPx: Float = 18f,
    /** Relative change of the finger distance that starts a pinch. */
    val pinchThreshold: Float = 0.06f,
    /** Downward travel of a three-finger swipe that summons the keyboard. */
    val threeFingerSwipePx: Float = 64f,
)

/**
 * One thing a gesture asks the viewer to do. Local actions change the view only; remote
 * actions are wire events and must go through [RemoteInputDispatcher].
 */
sealed interface RemoteGestureAction {
    /** Magnify the local picture by [factor] around `(focusX, focusY)` in view pixels. */
    data class LocalZoom(val factor: Float, val focusX: Float, val focusY: Float) : RemoteGestureAction

    /** Move the local picture by `(dx, dy)` view pixels. */
    data class LocalPan(val dx: Float, val dy: Float) : RemoteGestureAction

    /** Move the remote pointer by `(dx, dy)` view pixels (relative mode). */
    data class RemotePointer(val dx: Float, val dy: Float) : RemoteGestureAction

    /** Press and release [button] at `(x, y)` in view pixels (a tap). */
    data class RemoteClick(val button: PointerButton, val x: Float, val y: Float) : RemoteGestureAction

    /** Right click at `(x, y)`; from a long press or a two-finger tap. */
    data class RightClick(val x: Float, val y: Float) : RemoteGestureAction

    /** Wheel scroll; positive [dy] scrolls the remote desktop down. */
    data class Scroll(val dx: Float, val dy: Float) : RemoteGestureAction

    /** Hold [button] down at the current pointer position (drag lock). */
    data class PointerDown(val button: PointerButton) : RemoteGestureAction

    /** Release [button] at the current pointer position. */
    data class PointerUp(val button: PointerButton) : RemoteGestureAction

    /** The drag lock was armed ([locked] = true) or released. */
    data class DragLock(val locked: Boolean) : RemoteGestureAction

    /** Summon the software keyboard. */
    data object ShowKeyboard : RemoteGestureAction

    /** Release every key and button the host believes is held. */
    data object ReleaseAll : RemoteGestureAction
}
