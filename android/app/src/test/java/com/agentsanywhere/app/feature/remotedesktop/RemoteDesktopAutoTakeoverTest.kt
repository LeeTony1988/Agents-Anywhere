package com.agentsanywhere.app.feature.remotedesktop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopAutoTakeoverTest {
    @Test
    fun aControllerThatReleasesControlDoesNotGetItBackAutomatically() {
        val policy = RemoteDesktopAutoTakeover()

        // The create response already made this client the controller.
        assertFalse(policy.shouldTakeControl("rds_1", controlling = true))
        // User taps 「释放控制」: same session, now not controlling.
        assertFalse(policy.shouldTakeControl("rds_1", controlling = false))
        // A later session (retry or re-entry) may auto-take again.
        assertTrue(policy.shouldTakeControl("rds_2", controlling = false))
    }

    @Test
    fun aViewerThatJoinedBeforeTheHostWasReadyTakesControlOnce() {
        val policy = RemoteDesktopAutoTakeover()

        assertTrue(policy.shouldTakeControl("rds_1", controlling = false))
        // The follow-up live event after takeControl must not send a second request.
        assertFalse(policy.shouldTakeControl("rds_1", controlling = true))
    }
}
