package com.agentsanywhere.app.feature.remotedesktop.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rate limiting is measured on an injected clock, so a full second of traffic is a loop
 * instead of a sleep.
 */
class RemoteInputThrottleTest {

    private var now = 0L
    private val throttle = RemoteInputThrottle(clock = { now })

    @Test
    fun oneHundredTwentyEventsPerSecondPassAndTheNextIsDropped() {
        repeat(MAX_REMOTE_INPUT_EVENTS_PER_SECOND) {
            assertTrue("Event ${it + 1} should fit the window", throttle.tryAcquire(now))
        }
        assertEquals(120, throttle.eventsInWindow)

        assertFalse("The 121st event in the same second must be dropped", throttle.tryAcquire(now))
        assertEquals(120, throttle.eventsInWindow)
    }

    @Test
    fun theWindowRecoversExactlyOneSecondAfterTheOldestEvent() {
        repeat(120) { throttle.tryAcquire(now) }

        now = REMOTE_INPUT_WINDOW_MILLIS - 1
        assertFalse(throttle.tryAcquire(now))

        now = REMOTE_INPUT_WINDOW_MILLIS
        assertTrue("A full second after the first event the slot frees up again", throttle.tryAcquire(now))
        assertEquals(1, throttle.eventsInWindow)
    }

    @Test
    fun theLimitIsSlidingNotPerWallClockSecond() {
        // A burst that starts mid-second must not get a fresh budget when the second rolls over.
        now = 900
        repeat(120) {
            assertTrue(throttle.tryAcquire(now))
            now += 1
        }

        assertFalse(throttle.tryAcquire(now))

        now = 900 + REMOTE_INPUT_WINDOW_MILLIS
        assertTrue(throttle.tryAcquire(now))
    }

    @Test
    fun rateLimitedPointerMovesAreCoalescedAndDiscreteEventsAreDropped() {
        repeat(120) { throttle.tryAcquire(now) }

        assertEquals(RemoteInputDecision.Coalesced, throttle.offer(RemoteInputKind.PointerMove, now))
        assertTrue(throttle.hasCoalescedMove)
        assertEquals(RemoteInputDecision.Dropped, throttle.offer(RemoteInputKind.Key, now))
        assertEquals(RemoteInputDecision.Dropped, throttle.offer(RemoteInputKind.PointerButton, now))
        assertEquals(RemoteInputDecision.Dropped, throttle.offer(RemoteInputKind.ReleaseAll, now))

        assertTrue("The pending move is consumed once", throttle.takeCoalescedMove())
        assertFalse(throttle.takeCoalescedMove())
        assertFalse(throttle.hasCoalescedMove)
    }

    @Test
    fun acceptedEventsReportSendDecision() {
        assertEquals(RemoteInputDecision.Send, throttle.offer(RemoteInputKind.PointerMove, now))
        assertEquals(RemoteInputDecision.Send, throttle.offer(RemoteInputKind.PointerButton, now))
        assertEquals(RemoteInputDecision.Send, throttle.offer(RemoteInputKind.Key, now))
        assertEquals(3, throttle.eventsInWindow)
    }

    @Test
    fun resetClearsTheWindowAndPendingMove() {
        repeat(120) { throttle.tryAcquire(now) }
        throttle.offer(RemoteInputKind.PointerMove, now)
        assertTrue(throttle.hasCoalescedMove)

        throttle.reset()

        assertEquals(0, throttle.eventsInWindow)
        assertFalse(throttle.hasCoalescedMove)
        assertTrue(throttle.tryAcquire(now))
    }
}
