package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class RemoteDesktopTokenRefreshTest {
    private val now = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()

    @Test
    fun viewerTokenRenewsEightMinutesAfterIssue() {
        // Viewer tokens live 600 s; the client renews with 120 s left.
        assertEquals(480_000L, tokenRefreshDelayMillis("2026-11-13T00:10:00Z", now))
        assertFalse(shouldRefreshToken("2026-11-13T00:10:00Z", now))
    }

    @Test
    fun exactlyTwoMinutesLeftRefreshesNow() {
        assertEquals(0L, tokenRefreshDelayMillis("2026-11-13T00:02:00Z", now))
        assertTrue(shouldRefreshToken("2026-11-13T00:02:00Z", now))
    }

    @Test
    fun oneHundredNineteenSecondsLeftRefreshesNow() {
        assertEquals(0L, tokenRefreshDelayMillis("2026-11-13T00:01:59Z", now))
        assertTrue(shouldRefreshToken("2026-11-13T00:01:59Z", now))
    }

    @Test
    fun oneMillisecondAboveTheThresholdStillWaits() {
        assertEquals(1L, tokenRefreshDelayMillis("2026-11-13T00:02:00.001Z", now))
        assertFalse(shouldRefreshToken("2026-11-13T00:02:00.001Z", now))
    }

    @Test
    fun expiredTokenRefreshesNow() {
        assertEquals(0L, tokenRefreshDelayMillis("2026-11-12T23:59:00Z", now))
        assertTrue(shouldRefreshToken("2026-11-12T23:59:00Z", now))
        assertEquals(0L, tokenRefreshDelayMillis("2026-11-13T00:00:00Z", now))
    }

    @Test
    fun offsetTimestampsParseLikeUtcOnes() {
        assertEquals(480_000L, tokenRefreshDelayMillis("2026-11-13T08:10:00+08:00", now))
    }

    @Test
    fun missingOrBrokenExpiryRefreshesImmediately() {
        assertEquals(0L, tokenRefreshDelayMillis(null, now))
        assertEquals(0L, tokenRefreshDelayMillis("", now))
        assertEquals(0L, tokenRefreshDelayMillis("soon", now))
        assertNull(parseRemoteDesktopTimestampMillis("soon"))
    }

    @Test
    fun grantOverloadUsesTheTokenExpiry() {
        val grant = RemoteDesktopMediaGrant(
            provider = "livekit",
            url = "wss://rtc.example.com",
            room = "rd_rds_01JABCDEF",
            identity = "viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
            token = "eyJ",
            tokenExpiresAt = "2026-11-13T00:02:00Z",
            canPublishData = false,
        )

        assertEquals(0L, tokenRefreshDelayMillis(grant, now))
    }
}
