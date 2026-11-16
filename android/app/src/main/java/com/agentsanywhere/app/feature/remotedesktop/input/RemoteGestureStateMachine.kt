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
 * | single-finger drag | [RemoteGestureAction.RemotePointerDown] at the touch point, then [RemoteGestureAction.RemotePointer] with the left button held, released on lift |
 * | long press 500 ms | [RemoteGestureAction.RightClick] |
 * | two-finger vertical swipe | [RemoteGestureAction.Scroll] while the picture fits, [RemoteGestureAction.LocalPan] once it is zoomed |
 * | two-finger pinch | [RemoteGestureAction.LocalZoom] |
 * | double tap | [RemoteGestureAction.DragLock] plus a held left button |
 * | three-finger swipe down | [RemoteGestureAction.ShowKeyboard] |
 *
 * The table above is [RemotePointerMode.Direct]. In [RemotePointerMode.Mouse] a
 * one-finger swipe only moves the cursor; a tap clicks at the current cursor, a long
 * press right-clicks there, and the double-tap drag lock remains the way to drag. The
 * floating trackpad also lets the user put a finger on the cursor ring itself: that
 * starts a held left button immediately ([down] with `holdButton = true`), so the finger
 * can drag the remote cursor while the ring is highlighted, and lifting releases it.
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
    pointerMode: RemotePointerMode = RemotePointerMode.Direct,
) {
    private enum class Phase { Idle, Single, Two, Three }

    private enum class TwoFingerMode { Undecided, Zoom, Scroll, Pan }

    private var phase = Phase.Idle
    private var pointerCount = 0
    private var pointerMode = pointerMode

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastMoveAt = 0L
    private var lastSpread = 0f

    private var pendingDx = 0f
    private var pendingDy = 0f
    private var movedBeyondSlop = false
    private var longPressFired = false

    private var twoFingerMode = TwoFingerMode.Undecided
    private var accumulated = 0f
    private var keyboardShown = false
    /**
     * True once the current gesture ever had more than one pointer. Fingers rarely lift
     * in the same frame, so the last remaining finger must not turn a finished two-finger
     * scroll/pinch into a tap (and a stray click on the remote desktop).
     */
    private var multiFinger = false
    /**
     * A two-finger tap normally lifts both fingers in one event. If one finger lifts
     * first, the remaining finger must still deliver the right click when it lifts.
     */
    private var pendingTwoFingerTap = false

    private var dragLocked = false
    /**
     * True while a single-finger drag holds the left button down. A drag on a touchscreen
     * is a mouse drag, not a hover: pressing at the touch-down point before moving is what
     * lets the remote window select text, move a window or drag a scrollbar.
     */
    private var dragButtonDown = false
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

    /** True once the current gesture used more than one pointer. */
    val isMultiFinger: Boolean
        get() = multiFinger

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

    /**
     * Switches between direct touch and trackpad semantics.
     *
     * A mode change while a drag is held would leave the host with a stuck button, so
     * the machine releases the current gesture first. The caller dispatches the returned
     * actions through [RemoteInputDispatcher].
     */
    fun setPointerMode(mode: RemotePointerMode): List<RemoteGestureAction> {
        if (pointerMode == mode) return emptyList()
        pointerMode = mode
        return if (dragLocked || dragButtonDown) cancel() else emptyList()
    }

    val mode: RemotePointerMode
        get() = pointerMode

    /**
     * Starts a gesture; [spread] is the distance between two fingers (0 for one finger).
     *
     * [holdButton] is the trackpad's "grab the cursor ring" path: the button goes down at
     * the current cursor position before the finger moves, so the same swipe drags the
     * remote window/text instead of only moving the pointer. The caller releases it by
     * calling [up] (or [cancel]); a press with no motion still becomes a normal click.
     */
    fun down(
        x: Float,
        y: Float,
        timeMillis: Long,
        pointerCount: Int = 1,
        spread: Float = 0f,
        holdButton: Boolean = false,
    ): List<RemoteGestureAction> {
        // A new down while a drag still holds the button (a second finger, or a new gesture
        // after an interruption) must not leave the host with a stuck left button.
        val release = if (dragButtonDown) {
            dragButtonDown = false
            listOf(RemoteGestureAction.PointerUp(PointerButton.Left))
        } else {
            emptyList()
        }
        this.pointerCount = pointerCount.coerceAtLeast(1)
        multiFinger = this.pointerCount > 1
        pendingTwoFingerTap = false
        phase = when (this.pointerCount) {
            1 -> Phase.Single
            2 -> Phase.Two
            else -> Phase.Three
        }
        downX = x
        downY = y
        lastX = x
        lastY = y
        lastMoveAt = timeMillis
        lastSpread = spread
        pendingDx = 0f
        pendingDy = 0f
        movedBeyondSlop = false
        longPressFired = false
        twoFingerMode = TwoFingerMode.Undecided
        accumulated = 0f
        keyboardShown = false
        // Grabbing the ring while the drag lock is already holding the button must not send
        // a duplicate down; the locked drag keeps working through the normal move path.
        val grab = holdButton && this.pointerCount == 1 && pointerMode == RemotePointerMode.Mouse && !dragLocked
        if (grab) {
            dragButtonDown = true
            return release + RemoteGestureAction.PointerDown(PointerButton.Left)
        }
        return release
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
        val reanchored = if (resolvedCount != this.pointerCount) {
            reanchor(x, y, resolvedCount, spread, timeMillis)
        } else {
            emptyList()
        }
        val dx = x - lastX
        val dy = y - lastY
        lastX = x
        lastY = y
        // In trackpad mode small movements must reach the cursor immediately; the
        // tap slop only exists to separate a direct-touch tap from a drag. A tap in
        // mouse mode clicks at the cursor and does not need that protection.
        val slop = if (pointerMode == RemotePointerMode.Mouse) {
            config.mouseTapSlopPx
        } else {
            config.tapSlopPx
        }
        if (!movedBeyondSlop && (abs(x - downX) > slop || abs(y - downY) > slop)) {
            movedBeyondSlop = true
            pendingTwoFingerTap = false
            // A drag is not the first tap of a double tap; otherwise a drag followed by a
            // quick tap could arm the drag lock unexpectedly.
            lastTapAt = null
        }
        return reanchored + when (phase) {
            Phase.Single -> singleMove(dx, dy, timeMillis)
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
            Phase.Single -> when {
                // A drag holds the left button, so releasing the finger must release the
                // button at the last position instead of turning into a click.
                dragButtonDown -> {
                    dragButtonDown = false
                    buildList {
                        val dx = x - lastX
                        val dy = y - lastY
                        if (dx != 0f || dy != 0f) {
                            add(
                                RemoteGestureAction.RemotePointer(
                                    dx = dx,
                                    dy = dy,
                                    elapsedMillis = (timeMillis - lastMoveAt).coerceAtLeast(1L),
                                ),
                            )
                        }
                        add(RemoteGestureAction.PointerUp(PointerButton.Left))
                    }
                }

                movedBeyondSlop || longPressFired -> emptyList()

                // A gesture that ever had two fingers is never a single-finger tap,
                // but a stationary two-finger tap that lifts one finger first still
                // becomes a right click when the last finger lifts.
                multiFinger -> if (pendingTwoFingerTap) {
                    if (pointerMode == RemotePointerMode.Mouse) {
                        listOf(RemoteGestureAction.RightClickAtCursor)
                    } else {
                        listOf(RemoteGestureAction.RightClick(x, y))
                    }
                } else {
                    emptyList()
                }

                else -> tapActions(x, y, timeMillis)
            }

            Phase.Two -> if (twoFingerMode == TwoFingerMode.Undecided && !movedBeyondSlop) {
                // A two-finger tap is the gesture for a right click, the mouse counterpart
                // of the long press that needs a still finger.
                if (pointerMode == RemotePointerMode.Mouse) {
                    listOf(RemoteGestureAction.RightClickAtCursor)
                } else {
                    listOf(RemoteGestureAction.RightClick(x, y))
                }
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
        // While the ring is grabbed the button is already down: a right click here would
        // fire during the user's drag setup and leave the wrong button pressed.
        if (phase != Phase.Single || movedBeyondSlop || longPressFired || dragButtonDown || multiFinger) {
            return emptyList()
        }
        longPressFired = true
        return if (pointerMode == RemotePointerMode.Mouse) {
            listOf(RemoteGestureAction.RightClickAtCursor)
        } else {
            listOf(RemoteGestureAction.RightClick(downX, downY))
        }
    }

    /**
     * Explicit double-tap entry, for a host that detects the double tap itself (Compose's
     * `detectTapGestures(onDoubleTap = ...)`). The gesture state machine normally derives it
     * from [up] timestamps.
     */
    fun doubleTap(): List<RemoteGestureAction> = toggleDragLock()

    /** The gesture was interrupted: release a held button so the host does not stick. */
    fun cancel(): List<RemoteGestureAction> {
        val actions = if (dragLocked || dragButtonDown) {
            dragLocked = false
            dragButtonDown = false
            listOf(RemoteGestureAction.ReleaseAll)
        } else {
            emptyList()
        }
        reset()
        return actions
    }

    // ---- phases ------------------------------------------------------------

    private fun singleMove(dx: Float, dy: Float, timeMillis: Long): List<RemoteGestureAction> {
        if (longPressFired) return emptyList()
        val elapsed = (timeMillis - lastMoveAt).coerceAtLeast(1L)
        if (pointerMode == RemotePointerMode.Mouse) {
            // Trackpad mode: moving the finger only moves the cursor; the button is
            // pressed only by a tap/long press, the ring grab or the explicit drag lock.
            // Every sample is sent, including sub-slop motion: a dead zone before the
            // cursor reacts is exactly what makes a trackpad feel disconnected.
            if (dx == 0f && dy == 0f) return emptyList()
            lastMoveAt = timeMillis
            return listOf(RemoteGestureAction.RemotePointer(dx, dy, elapsed))
        }
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
        lastMoveAt = timeMillis
        // The first move that crosses the slop presses the button at the point where the
        // finger went down, then applies the accumulated motion. Without this press the
        // host receives hover moves and the remote window never sees a drag.
        if (!dragLocked && !dragButtonDown) {
            dragButtonDown = true
            return listOf(
                RemoteGestureAction.RemotePointerDown(PointerButton.Left, downX, downY),
                RemoteGestureAction.RemotePointer(totalDx, totalDy, elapsed),
            )
        }
        return listOf(RemoteGestureAction.RemotePointer(totalDx, totalDy, elapsed))
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
        // Match the same slop as the tap itself: the trackpad's 4 px precision should not
        // be paired with a 16 px double-tap window.
        val doubleTapSlop = if (pointerMode == RemotePointerMode.Mouse) {
            config.mouseTapSlopPx
        } else {
            config.tapSlopPx
        }
        val doubleTap = previousTap != null &&
            timeMillis - previousTap <= config.doubleTapMillis &&
            abs(x - lastTapX) <= doubleTapSlop &&
            abs(y - lastTapY) <= doubleTapSlop
        if (doubleTap) {
            lastTapAt = null
            return toggleDragLock()
        }
        // While the drag lock holds the left button, a tap must not inject a click at
        // the cursor: on Windows the duplicate down is dropped but the up would release
        // the drag and leave the lock state lying. Wait for the second tap to unlock.
        if (dragLocked) {
            lastTapAt = timeMillis
            lastTapX = x
            lastTapY = y
            return emptyList()
        }
        lastTapAt = timeMillis
        lastTapX = x
        lastTapY = y
        return if (pointerMode == RemotePointerMode.Mouse) {
            // A tap is a click at the cursor, not a jump to the finger.
            listOf(RemoteGestureAction.ClickAtCursor(PointerButton.Left))
        } else {
            listOf(RemoteGestureAction.RemoteClick(PointerButton.Left, x, y))
        }
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

    private fun reanchor(
        x: Float,
        y: Float,
        count: Int,
        spread: Float,
        timeMillis: Long,
    ): List<RemoteGestureAction> {
        // A second finger turning a one-finger drag into a scroll/pinch must let go of the
        // left button, otherwise the host keeps it pressed after the gesture ends.
        val release = if (dragButtonDown) {
            dragButtonDown = false
            listOf(RemoteGestureAction.PointerUp(PointerButton.Left))
        } else {
            emptyList()
        }
        // Remember a multi-finger gesture across a 2→1 lift so the remaining finger is
        // not mistaken for a fresh single-finger tap. If the two-finger gesture had not
        // moved, keep the pending right click for the final lift.
        val wasTwoFingerTap = pointerCount == 2 &&
            count == 1 &&
            !movedBeyondSlop &&
            twoFingerMode == TwoFingerMode.Undecided
        if (wasTwoFingerTap) pendingTwoFingerTap = true
        multiFinger = multiFinger || pointerCount > 1 || count > 1
        pointerCount = count
        phase = when (count) {
            1 -> Phase.Single
            2 -> Phase.Two
            else -> Phase.Three
        }
        lastX = x
        lastY = y
        lastMoveAt = timeMillis
        lastSpread = spread
        twoFingerMode = TwoFingerMode.Undecided
        accumulated = 0f
        pendingDx = 0f
        pendingDy = 0f
        // A finger added mid-drag keeps the drag's "moved" state so the release is not a tap.
        keyboardShown = false
        return release
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
        multiFinger = false
        pendingTwoFingerTap = false
        lastSpread = 0f
        downX = 0f
        downY = 0f
        lastMoveAt = 0L
        dragButtonDown = false
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
    /** Smaller threshold in trackpad mode so micro-adjustments are not swallowed. */
    val mouseTapSlopPx: Float = 4f,
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

    /**
     * Move the remote pointer by `(dx, dy)` view pixels (relative mode).
     * [elapsedMillis] lets the dispatcher apply speed-dependent gain.
     */
    data class RemotePointer(
        val dx: Float,
        val dy: Float,
        val elapsedMillis: Long = 16L,
    ) : RemoteGestureAction

    /** Press and release [button] at `(x, y)` in view pixels (a tap). */
    data class RemoteClick(val button: PointerButton, val x: Float, val y: Float) : RemoteGestureAction

    /** Left click at the current pointer position (trackpad mode). */
    data class ClickAtCursor(val button: PointerButton) : RemoteGestureAction

    /** Right click at `(x, y)`; from a long press or a two-finger tap. */
    data class RightClick(val x: Float, val y: Float) : RemoteGestureAction

    /** Right click at the current pointer position (trackpad mode). */
    data object RightClickAtCursor : RemoteGestureAction

    /** Wheel scroll; positive [dy] scrolls the remote desktop down. */
    data class Scroll(val dx: Float, val dy: Float) : RemoteGestureAction

    /** Hold [button] down at `(x, y)` in view pixels; used to start a drag. */
    data class RemotePointerDown(
        val button: PointerButton,
        val x: Float,
        val y: Float,
    ) : RemoteGestureAction

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
