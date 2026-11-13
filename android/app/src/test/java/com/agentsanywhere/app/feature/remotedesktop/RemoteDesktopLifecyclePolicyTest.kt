package com.agentsanywhere.app.feature.remotedesktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P4-05 background rule of `docs/remote-desktop.md` section 8.3: leaving the app stops
 * rendering but keeps a 30 s reconnection window, and input is released exactly once per
 * pause. The clock is injected, so the window is tested without sleeping.
 */
class RemoteDesktopLifecyclePolicyTest {

    private var now = 1_000L
    private val policy = RemoteDesktopLifecyclePolicy(clock = { now })

    @Test
    fun stoppingDetachesTheRendererAndKeepsTheRoom() {
        assertEquals(
            listOf(
                RemoteDesktopLifecycleAction.DetachRenderer,
                RemoteDesktopLifecycleAction.ReleaseInput,
            ),
            policy.onStop(),
        )
        assertTrue(policy.isBackgrounded)
    }

    @Test
    fun returningInsideTheWindowReattachesTheRenderer() {
        policy.onStop()

        now += REMOTE_DESKTOP_BACKGROUND_GRACE_MILLIS - 1L

        assertEquals(listOf(RemoteDesktopLifecycleAction.ReattachRenderer), policy.onStart())
        assertFalse(policy.isBackgrounded)
        assertNull(policy.backgroundedForMillis)
    }

    @Test
    fun returningAfterTheWindowEndsTheSession() {
        policy.onStop()

        now += REMOTE_DESKTOP_BACKGROUND_GRACE_MILLIS

        assertEquals(listOf(RemoteDesktopLifecycleAction.EndSession), policy.onStart())
    }

    @Test
    fun theGraceWindowIsMeasuredFromTheStopNotFromTheLastEvent() {
        policy.onStop()
        now += 20_000L
        policy.onStop()

        assertEquals(0L, policy.backgroundedForMillis)
        now += 29_000L
        assertEquals(
            "The window restarted on the second stop",
            listOf(RemoteDesktopLifecycleAction.ReattachRenderer),
            policy.onStart(),
        )
    }

    @Test
    fun inputIsReleasedOnlyOncePerPause() {
        assertEquals(listOf(RemoteDesktopLifecycleAction.ReleaseInput), policy.onPauseInput())
        assertEquals(emptyList<RemoteDesktopLifecycleAction>(), policy.onPauseInput())
    }

    @Test
    fun aForegroundReturnRearmsTheInputRelease() {
        policy.onPauseInput()
        policy.onStop()
        policy.onStart()

        assertEquals(listOf(RemoteDesktopLifecycleAction.ReleaseInput), policy.onPauseInput())
    }

    @Test
    fun stopSendsTheReleaseOnlyWhenThePauseDidNotAlready() {
        policy.onPauseInput()

        assertEquals(
            listOf(RemoteDesktopLifecycleAction.DetachRenderer),
            policy.onStop(),
        )
    }

    @Test
    fun startingWithoutABackgroundReturnsNothing() {
        assertEquals(emptyList<RemoteDesktopLifecycleAction>(), policy.onStart())
    }

    @Test
    fun resetForgetsAPendingWindow() {
        policy.onStop()
        policy.reset()

        assertFalse(policy.isBackgrounded)
        assertEquals(emptyList<RemoteDesktopLifecycleAction>(), policy.onStart())
    }
}
