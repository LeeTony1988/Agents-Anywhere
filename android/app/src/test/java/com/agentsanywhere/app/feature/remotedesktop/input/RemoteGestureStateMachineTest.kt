package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The §8.1 table of `docs/remote-desktop.md` as executable cases: tap, drag, long press,
 * two-finger scroll/pinch, double-tap drag lock, three-finger keyboard and the release of a
 * held button. The machine is pure, so every case is a sequence of coordinates and times.
 */
class RemoteGestureStateMachineTest {

    private val machine = RemoteGestureStateMachine()

    @Test
    fun singleTapSendsOneLeftClickAtTheTouchPoint() {
        machine.down(x = 100f, y = 200f, timeMillis = 0L)

        val actions = machine.up(x = 101f, y = 203f, timeMillis = 120L)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.RemoteClick(PointerButton.Left, 101f, 203f)), actions)
        assertFalse(machine.isDragLocked)
    }

    @Test
    fun singleFingerDragPressesTheLeftButtonAtTheTouchPointAndReleasesIt() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L)

        val first = machine.move(x = 140f, y = 100f, timeMillis = 16L)
        val second = machine.move(x = 170f, y = 90f, timeMillis = 32L)
        val released = machine.up(x = 170f, y = 90f, timeMillis = 48L)

        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointerDown(PointerButton.Left, 100f, 100f),
                RemoteGestureAction.RemotePointer(40f, 0f),
            ),
            first,
        )
        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.RemotePointer(30f, -10f)), second)
        assertEquals(
            "A drag releases the button instead of clicking",
            listOf<RemoteGestureAction>(RemoteGestureAction.PointerUp(PointerButton.Left)),
            released,
        )
    }

    @Test
    fun subSlopJitterIsCarriedIntoTheFirstPointerMove() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L)

        assertEquals("Below the tap slop nothing is sent", emptyList<RemoteGestureAction>(), machine.move(106f, 100f, 10L))
        // 20 px of travel crosses the 16 px slop; the 6 px already spent must not be lost.
        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointerDown(PointerButton.Left, 100f, 100f),
                RemoteGestureAction.RemotePointer(20f, 0f, elapsedMillis = 20L),
            ),
            machine.move(120f, 100f, 20L),
        )
    }

    @Test
    fun mouseModeMovesThePointerWithoutPressingALeftButton() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L)

        val moved = mouse.move(x = 120f, y = 100f, timeMillis = 20L)
        val lifted = mouse.up(x = 120f, y = 100f, timeMillis = 40L)

        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointer(dx = 20f, dy = 0f, elapsedMillis = 20L),
            ),
            moved,
        )
        assertTrue("A moved trackpad gesture is not a click", lifted.isEmpty())
    }

    @Test
    fun mouseModeSendsTheFirstPixelWithoutADeadZone() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L)

        assertEquals(
            "A 3 px adjustment must reach the cursor immediately, not after the tap slop",
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointer(dx = 3f, dy = 0f, elapsedMillis = 10L),
            ),
            mouse.move(103f, 100f, 10L),
        )
        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointer(dx = 3f, dy = 0f, elapsedMillis = 10L),
            ),
            mouse.move(106f, 100f, 20L),
        )
    }

    @Test
    fun mouseModeHoldingTheCursorRingPressesAndReleasesTheLeftButton() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)

        assertEquals(
            "Grabbing the ring presses the button before the finger moves",
            listOf<RemoteGestureAction>(RemoteGestureAction.PointerDown(PointerButton.Left)),
            mouse.down(x = 100f, y = 100f, timeMillis = 0L, holdButton = true),
        )
        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.RemotePointer(dx = 12f, dy = 4f, elapsedMillis = 16L),
            ),
            mouse.move(x = 112f, y = 104f, timeMillis = 16L),
        )
        assertEquals(
            "Lifting after the grab releases the button, not just the finger",
            listOf<RemoteGestureAction>(RemoteGestureAction.PointerUp(PointerButton.Left)),
            mouse.up(x = 112f, y = 104f, timeMillis = 32L),
        )
        assertFalse(mouse.isDragLocked)
    }

    @Test
    fun grabbingTheCursorRingSuppressesTheLongPressRightClick() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L, holdButton = true)

        assertTrue(
            "A stationary grabbed ring is a held drag, not a right click",
            mouse.longPress(timeMillis = 600L).isEmpty(),
        )
    }

    @Test
    fun grabbingTheCursorRingWhileDragLockedDoesNotPressTheButtonTwice() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.doubleTap()

        assertTrue(
            "The drag lock already holds the left button",
            mouse.down(x = 100f, y = 100f, timeMillis = 100L, holdButton = true).isEmpty(),
        )
    }

    @Test
    fun mouseModeTapsAtTheCurrentCursorInsteadOfTheTouchPoint() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L)

        val actions = mouse.up(x = 101f, y = 102f, timeMillis = 100L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.ClickAtCursor(PointerButton.Left)),
            actions,
        )
    }

    @Test
    fun mouseModeLongPressIsARightClickAtTheCursor() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClickAtCursor),
            mouse.longPress(timeMillis = 500L),
        )
    }

    @Test
    fun switchingPointerModeMidDragReleasesTheHeldButton() {
        val machine = RemoteGestureStateMachine()
        machine.down(x = 100f, y = 100f, timeMillis = 0L)
        machine.move(x = 160f, y = 100f, timeMillis = 20L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.ReleaseAll),
            machine.setPointerMode(RemotePointerMode.Mouse),
        )
        assertEquals(RemotePointerMode.Mouse, machine.mode)
    }

    @Test
    fun longPressSendsARightClickAndSuppressesTheTap() {
        machine.down(x = 50f, y = 60f, timeMillis = 0L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClick(50f, 60f)),
            machine.longPress(timeMillis = 500L),
        )
        assertTrue("Releasing after a long press must not click again", machine.up(50f, 60f, 560L).isEmpty())
    }

    @Test
    fun longPressAfterMovementIsNotARightClick() {
        machine.down(x = 10f, y = 10f, timeMillis = 0L)
        machine.move(x = 80f, y = 10f, timeMillis = 100L)

        assertTrue(machine.longPress(timeMillis = 600L).isEmpty())
    }

    @Test
    fun twoFingerVerticalSwipeScrollsWhenThePictureFits() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L, pointerCount = 2, spread = 120f)

        val actions = machine.move(x = 100f, y = 130f, timeMillis = 20L, pointerCount = 2, spread = 120f)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.Scroll(dx = 0f, dy = 30f)), actions)
        assertTrue(
            "A two-finger gesture must never move the pointer",
            machine.move(100f, 160f, 40L, pointerCount = 2, spread = 120f)
                .none { it is RemoteGestureAction.RemotePointer },
        )
    }

    @Test
    fun twoFingerDragPansTheLocalPictureOnceZoomed() {
        val zoomed = RemoteGestureStateMachine(initiallyZoomed = true)
        zoomed.down(x = 100f, y = 100f, timeMillis = 0L, pointerCount = 2, spread = 120f)

        val actions = zoomed.move(x = 100f, y = 130f, timeMillis = 20L, pointerCount = 2, spread = 120f)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.LocalPan(dx = 0f, dy = 30f)), actions)
    }

    @Test
    fun aNewTwoFingerDragPansOnceThePictureIsZoomed() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L, pointerCount = 2, spread = 120f)
        machine.move(100f, 100f, 10L, pointerCount = 2, spread = 150f)
        machine.up(100f, 100f, 20L, pointerCount = 2, spread = 150f)

        machine.setZoomed(true)
        machine.down(x = 100f, y = 100f, timeMillis = 200L, pointerCount = 2, spread = 150f)
        val actions = machine.move(x = 100f, y = 140f, timeMillis = 220L, pointerCount = 2, spread = 150f)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.LocalPan(dx = 0f, dy = 40f)), actions)
    }

    @Test
    fun pinchSendsAZoomFactorAroundTheGestureCentre() {
        machine.down(x = 200f, y = 300f, timeMillis = 0L, pointerCount = 2, spread = 100f)

        val actions = machine.move(x = 200f, y = 300f, timeMillis = 16L, pointerCount = 2, spread = 150f)

        val zoom = actions.single() as RemoteGestureAction.LocalZoom
        assertEquals(1.5f, zoom.factor, TOLERANCE)
        assertEquals(200f, zoom.focusX, TOLERANCE)
        assertEquals(300f, zoom.focusY, TOLERANCE)
    }

    @Test
    fun liftingOneFingerFromATwoFingerScrollDoesNotClickOnTheFinalLift() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 100f, y = 100f, timeMillis = 0L, pointerCount = 2, spread = 120f)
        mouse.move(x = 100f, y = 130f, timeMillis = 20L, pointerCount = 2, spread = 120f)

        assertTrue(
            "Dropping to one finger only re-anchors the scroll",
            mouse.move(x = 100f, y = 130f, timeMillis = 40L, pointerCount = 1, spread = 0f).isEmpty(),
        )
        assertTrue(mouse.isMultiFinger)
        assertTrue(
            "A multi-finger gesture must not turn the remaining finger into a long-press right click",
            mouse.longPress(timeMillis = 600L).isEmpty(),
        )
        assertTrue(
            "The last finger of a two-finger scroll must not click",
            mouse.up(x = 100f, y = 130f, timeMillis = 660L, pointerCount = 1, spread = 0f).isEmpty(),
        )
    }

    @Test
    fun aTwoFingerTapIsARightClick() {
        machine.down(x = 120f, y = 140f, timeMillis = 0L, pointerCount = 2, spread = 110f)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClick(120f, 140f)),
            machine.up(x = 120f, y = 140f, timeMillis = 90L, pointerCount = 2, spread = 110f),
        )
    }

    @Test
    fun aTwoFingerTapStillRightClicksWhenTheFirstFingerLiftsEarly() {
        val mouse = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
        mouse.down(x = 120f, y = 140f, timeMillis = 0L, pointerCount = 2, spread = 110f)

        assertTrue(
            "Dropping to one finger only re-anchors the pending tap",
            mouse.move(x = 120f, y = 140f, timeMillis = 30L, pointerCount = 1, spread = 0f).isEmpty(),
        )
        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClickAtCursor),
            mouse.up(x = 120f, y = 140f, timeMillis = 60L, pointerCount = 1, spread = 0f),
        )
    }

    @Test
    fun aDirectTouchTwoFingerTapStillRightClicksWhenFingersLiftSeparately() {
        val direct = RemoteGestureStateMachine()
        direct.down(x = 120f, y = 140f, timeMillis = 0L, pointerCount = 2, spread = 110f)
        direct.move(x = 120f, y = 140f, timeMillis = 30L, pointerCount = 1, spread = 0f)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClick(120f, 140f)),
            direct.up(x = 120f, y = 140f, timeMillis = 60L, pointerCount = 1, spread = 0f),
        )
    }

    @Test
    fun threeFingerSwipeDownSummonsTheKeyboardOnce() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L, pointerCount = 3)

        val first = machine.move(x = 100f, y = 140f, timeMillis = 20L, pointerCount = 3)
        val second = machine.move(x = 100f, y = 200f, timeMillis = 40L, pointerCount = 3)

        assertEquals("A partial swipe waits", emptyList<RemoteGestureAction>(), first)
        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.ShowKeyboard), second)
        assertTrue(
            "The keyboard is summoned once per gesture",
            machine.move(x = 100f, y = 260f, timeMillis = 60L, pointerCount = 3).isEmpty(),
        )
    }

    @Test
    fun tripleFingerSwipeUpDoesNotSummonTheKeyboard() {
        machine.down(x = 100f, y = 300f, timeMillis = 0L, pointerCount = 3)

        assertTrue(machine.move(100f, 200f, 30L, pointerCount = 3).isEmpty())
    }

    @Test
    fun doubleTapArmsTheDragLockAndASecondDoubleTapReleasesIt() {
        machine.down(100f, 100f, 0L)
        machine.up(100f, 100f, 60L)
        machine.down(101f, 100f, 120L)
        val locked = machine.up(101f, 100f, 180L)

        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.PointerDown(PointerButton.Left),
                RemoteGestureAction.DragLock(locked = true),
            ),
            locked,
        )
        assertTrue(machine.isDragLocked)

        machine.down(101f, 100f, 240L)
        assertTrue(
            "A tap while the drag lock holds the button must not click or release it",
            machine.up(101f, 100f, 280L).isEmpty(),
        )
        machine.down(101f, 100f, 300L)
        val unlocked = machine.up(101f, 100f, 340L)

        assertEquals(
            listOf<RemoteGestureAction>(
                RemoteGestureAction.PointerUp(PointerButton.Left),
                RemoteGestureAction.DragLock(locked = false),
            ),
            unlocked,
        )
        assertFalse(machine.isDragLocked)
    }

    @Test
    fun twoSlowTapsAreTwoClicksWhenTheyAreTooFarApartInTime() {
        machine.down(100f, 100f, 0L)
        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RemoteClick(PointerButton.Left, 100f, 100f)),
            machine.up(100f, 100f, 50L),
        )

        machine.down(100f, 100f, 900L)
        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RemoteClick(PointerButton.Left, 100f, 100f)),
            machine.up(100f, 100f, 950L),
        )
        assertFalse(machine.isDragLocked)
    }

    @Test
    fun twoTapsApartOnScreenAreTwoClicksWhenTheyAreNotOnTheSameSpot() {
        machine.down(100f, 100f, 0L)
        machine.up(100f, 100f, 50L)

        machine.down(400f, 400f, 90L)
        val actions = machine.up(400f, 400f, 140L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RemoteClick(PointerButton.Left, 400f, 400f)),
            actions,
        )
    }

    @Test
    fun cancelReleasesAHeldButtonExactlyOnce() {
        val locked = machine.doubleTap()
        assertTrue(locked.any { it is RemoteGestureAction.DragLock })

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.ReleaseAll),
            machine.cancel(),
        )
        assertTrue("A second cancel has nothing left to release", machine.cancel().isEmpty())
    }

    @Test
    fun movingReportedToThePointerIsIgnoredAfterALongPress() {
        machine.down(100f, 100f, 0L)
        machine.longPress(520L)

        assertTrue(machine.move(200f, 200f, 560L).isEmpty())
    }

    @Test
    fun cancellingADragReleasesTheLeftButton() {
        machine.down(100f, 100f, 0L)
        machine.move(160f, 100f, 20L)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.ReleaseAll),
            machine.cancel(),
        )
    }

    @Test
    fun addingASecondFingerMidDragReleasesTheLeftButtonAndReanchorsTheGesture() {
        machine.down(100f, 100f, 0L)
        machine.move(160f, 100f, 20L)

        val secondFinger = machine.down(160f, 100f, 30L, pointerCount = 2, spread = 80f)
        val actions = machine.move(160f, 130f, 50L, pointerCount = 2, spread = 80f)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.PointerUp(PointerButton.Left)),
            secondFinger,
        )
        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.Scroll(dx = 0f, dy = 30f)), actions)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
