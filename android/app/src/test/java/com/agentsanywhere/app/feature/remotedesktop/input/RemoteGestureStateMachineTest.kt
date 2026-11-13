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
    fun singleFingerDragMovesThePointerRelativelyAndNeverClicks() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L)

        val first = machine.move(x = 140f, y = 100f, timeMillis = 16L)
        val second = machine.move(x = 170f, y = 90f, timeMillis = 32L)
        val released = machine.up(x = 170f, y = 90f, timeMillis = 48L)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.RemotePointer(40f, 0f)), first)
        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.RemotePointer(30f, -10f)), second)
        assertTrue("A drag must not turn into a tap on release", released.isEmpty())
    }

    @Test
    fun subSlopJitterIsCarriedIntoTheFirstPointerMove() {
        machine.down(x = 100f, y = 100f, timeMillis = 0L)

        assertEquals("Below the tap slop nothing is sent", emptyList<RemoteGestureAction>(), machine.move(106f, 100f, 10L))
        // 20 px of travel crosses the 16 px slop; the 6 px already spent must not be lost.
        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RemotePointer(20f, 0f)),
            machine.move(120f, 100f, 20L),
        )
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
    fun aTwoFingerTapIsARightClick() {
        machine.down(x = 120f, y = 140f, timeMillis = 0L, pointerCount = 2, spread = 110f)

        assertEquals(
            listOf<RemoteGestureAction>(RemoteGestureAction.RightClick(120f, 140f)),
            machine.up(x = 120f, y = 140f, timeMillis = 90L, pointerCount = 2, spread = 110f),
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
        machine.up(101f, 100f, 280L)
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
    fun addingASecondFingerMidDragReanchorsTheGesture() {
        machine.down(100f, 100f, 0L)
        machine.move(160f, 100f, 20L)

        machine.down(160f, 100f, 30L, pointerCount = 2, spread = 80f)
        val actions = machine.move(160f, 130f, 50L, pointerCount = 2, spread = 80f)

        assertEquals(listOf<RemoteGestureAction>(RemoteGestureAction.Scroll(dx = 0f, dy = 30f)), actions)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
