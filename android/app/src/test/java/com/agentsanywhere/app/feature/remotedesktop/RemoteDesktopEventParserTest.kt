package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays the six Server events of
 * `contracts/remote-desktop/1.0/fixtures/valid/realtime-event/` plus one unknown type.
 * The payloads are copied verbatim from those fixtures.
 */
class RemoteDesktopEventParserTest {

    private val sessionId = "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C"

    @Test
    fun parsesSubscribedWithAnEmptyCursor() {
        val event = RemoteDesktopEventParser.parse(
            """
            {
              "type": "remote_desktop.subscribed",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "cursor": ""
              }
            }
            """.trimIndent(),
        )

        val subscribed = event as? RemoteDesktopServerEvent.Subscribed
            ?: error("Expected a Subscribed event, got $event")
        assertEquals(sessionId, subscribed.sessionId)
        assertEquals("", subscribed.cursor)
    }

    @Test
    fun parsesSessionUpdatedWithTheFullSessionSnapshot() {
        val event = RemoteDesktopEventParser.parse(SESSION_UPDATED)

        val updated = event as? RemoteDesktopServerEvent.SessionUpdated
            ?: error("Expected a SessionUpdated event, got $event")
        assertEquals(sessionId, updated.sessionId)
        val session = updated.session
        assertEquals(sessionId, session.id)
        assertEquals("conn_01J8Y2N5P7Q9R3S6T4V0W8X2Z", session.connectorId)
        assertEquals(RemoteDesktopMode.Control, session.mode)
        assertEquals(RemoteDesktopState.Live, session.state)
        assertEquals("viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8", session.controller)
        assertEquals("0", session.displayId)
        assertEquals(30, session.quality?.maxFps)
        assertEquals("rd_rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C", session.media?.room)
        assertEquals(29.7, session.stats?.fps ?: 0.0, 1e-6)
        assertEquals(2, session.viewerCount)
    }

    @Test
    fun parsesStatsUpdated() {
        val event = RemoteDesktopEventParser.parse(
            """
            {
              "type": "remote_desktop.stats.updated",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "stats": {
                  "fps": 29.7,
                  "bitrateKbps": 2610,
                  "rttMs": 38,
                  "jitterMs": 4.2,
                  "packetLossPct": 0.3,
                  "codec": "vp8",
                  "width": 1920,
                  "height": 1080,
                  "encoder": "libwebrtc-sw",
                  "subscribers": 2
                },
                "at": "2026-11-13T00:34:58Z"
              }
            }
            """.trimIndent(),
        )

        val stats = event as? RemoteDesktopServerEvent.StatsUpdated
            ?: error("Expected a StatsUpdated event, got $event")
        assertEquals(sessionId, stats.sessionId)
        assertEquals(29.7, stats.stats.fps, 1e-6)
        assertEquals(2610.0, stats.stats.bitrateKbps, 1e-6)
        assertEquals(1920, stats.stats.width)
        assertEquals(2, stats.stats.subscribers)
        assertEquals("2026-11-13T00:34:58Z", stats.at)
    }

    @Test
    fun parsesControlRevoked() {
        val event = RemoteDesktopEventParser.parse(
            """
            {
              "type": "remote_desktop.control.revoked",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "reason": "taken_over"
              }
            }
            """.trimIndent(),
        )

        val revoked = event as? RemoteDesktopServerEvent.ControlRevoked
            ?: error("Expected a ControlRevoked event, got $event")
        assertEquals(sessionId, revoked.sessionId)
        assertEquals(RemoteDesktopControlRevokedReason.TakenOver, revoked.reason)
        assertEquals("taken_over", revoked.reasonRaw)
    }

    @Test
    fun parsesEnded() {
        val event = RemoteDesktopEventParser.parse(
            """
            {
              "type": "remote_desktop.ended",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "endReason": "client_ended",
                "endedAt": "2026-11-13T00:41:12Z"
              }
            }
            """.trimIndent(),
        )

        val ended = event as? RemoteDesktopServerEvent.Ended
            ?: error("Expected an Ended event, got $event")
        assertEquals(sessionId, ended.sessionId)
        assertEquals(RemoteDesktopEndReason.ClientEnded, ended.endReason)
        assertEquals("client_ended", ended.endReasonRaw)
        assertEquals("2026-11-13T00:41:12Z", ended.endedAt)
    }

    @Test
    fun parsesErrorWithAStableCode() {
        val event = RemoteDesktopEventParser.parse(
            """
            {
              "type": "remote_desktop.error",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "code": "remote_desktop_unavailable",
                "message": "Connector is offline",
                "retryable": true
              }
            }
            """.trimIndent(),
        )

        val error = event as? RemoteDesktopServerEvent.Error
            ?: error("Expected an Error event, got $event")
        assertEquals(sessionId, error.sessionId)
        assertEquals(RemoteDesktopErrorCode.Unavailable, error.code)
        assertEquals("remote_desktop_unavailable", error.rawCode)
        assertEquals("Connector is offline", error.message)
        assertTrue(error.retryable)
    }

    @Test
    fun ignoresUnknownEventTypes() {
        val unknown = RemoteDesktopEventParser.parse(
            """{"type":"remote_desktop.clipboard.updated","payload":{"sessionId":"rds_1"}}""",
        )
        val keepalive = RemoteDesktopEventParser.parse("""{"type":"keepalive"}""")

        assertNull(unknown)
        assertNull(keepalive)
    }

    @Test
    fun dropsMalformedOrUnroutableFrames() {
        assertNull(RemoteDesktopEventParser.parse("not json"))
        assertNull(RemoteDesktopEventParser.parse("""{"payload":{"sessionId":"rds_1"}}"""))
        assertNull(RemoteDesktopEventParser.parse("""{"type":"remote_desktop.subscribed"}"""))
        // Every payload repeats sessionId; without it the event cannot be routed.
        assertNull(RemoteDesktopEventParser.parse("""{"type":"remote_desktop.ended","payload":{}}"""))
        // A session snapshot without the session object is incomplete.
        assertNull(
            RemoteDesktopEventParser.parse(
                """{"type":"remote_desktop.session.updated","payload":{"sessionId":"rds_1"}}""",
            ),
        )
    }

    @Test
    fun forwardCompatibleValuesDegradeInsteadOfThrowing() {
        val revoked = RemoteDesktopEventParser.parse(
            """
            {"type":"remote_desktop.control.revoked",
             "payload":{"sessionId":"rds_1","reason":"admin_pull_plug"}}
            """.trimIndent(),
        ) as RemoteDesktopServerEvent.ControlRevoked
        val ended = RemoteDesktopEventParser.parse(
            """
            {"type":"remote_desktop.ended",
             "payload":{"sessionId":"rds_1","endReason":"cosmic_ray"}}
            """.trimIndent(),
        ) as RemoteDesktopServerEvent.Ended

        assertEquals(RemoteDesktopControlRevokedReason.Unknown, revoked.reason)
        assertEquals("admin_pull_plug", revoked.reasonRaw)
        assertEquals(RemoteDesktopEndReason.Unknown, ended.endReason)
        assertEquals("cosmic_ray", ended.endReasonRaw)
        assertNull(ended.endedAt)
    }

    private companion object {
        const val SESSION_UPDATED = """
            {
              "type": "remote_desktop.session.updated",
              "payload": {
                "sessionId": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                "session": {
                  "id": "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                  "connectorId": "conn_01J8Y2N5P7Q9R3S6T4V0W8X2Z",
                  "mode": "control",
                  "displayId": "0",
                  "state": "live",
                  "endReason": null,
                  "controller": "viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
                  "controllerLeaseExpiresAt": "2026-11-13T00:35:00Z",
                  "linkedSessionId": null,
                  "media": {
                    "provider": "livekit",
                    "room": "rd_rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                    "url": "wss://rtc.example.com"
                  },
                  "quality": {
                    "maxFps": 30,
                    "maxBitrateKbps": 4000,
                    "maxWidth": 1920,
                    "preferCodec": "vp8"
                  },
                  "stats": {
                    "fps": 29.7,
                    "bitrateKbps": 2610,
                    "rttMs": 38,
                    "jitterMs": 4.2,
                    "packetLossPct": 0.3,
                    "codec": "vp8",
                    "width": 1920,
                    "height": 1080,
                    "encoder": "libwebrtc-sw",
                    "subscribers": 2
                  },
                  "error": null,
                  "createdAt": "2026-11-13T00:30:00Z",
                  "startedAt": "2026-11-13T00:30:02Z",
                  "endedAt": null,
                  "viewerCount": 2
                }
              }
            }
        """
    }
}
