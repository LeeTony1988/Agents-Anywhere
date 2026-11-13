package com.agentsanywhere.app.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RemoteDesktopApiTest {
    private val server = MockWebServer()
    private lateinit var serverUrl: String
    private lateinit var api: RemoteDesktopApi

    @Before
    fun setUp() {
        server.start()
        serverUrl = server.url("/").toString().trimEnd('/')
        api = RemoteDesktopApi(ApiClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun controlSessionWaitingForConsentCarriesNoMediaToken() {
        server.enqueue(
            jsonResponse(
                201,
                """
                {"session":{"id":"rds_01JABCDEF","connectorId":"conn_x","mode":"control",
                  "state":"awaiting_consent","quality":{"maxFps":30,"maxBitrateKbps":4000},
                  "viewerCount":1,"createdAt":"2026-11-13T00:00:00Z"},
                 "participantId":"pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
                 "media":null,
                 "ws":{"url":"/remote-desktop/sessions/rds_01JABCDEF/ws","ticket":"wst_1",
                   "expiresAt":"2026-11-13T00:10:00Z"}}
                """.trimIndent(),
            ),
        )

        val created = api.createSession(
            serverUrl = serverUrl,
            authorizationToken = "token",
            request = RemoteDesktopSessionCreateRequest(
                connectorId = "conn_x",
                mode = RemoteDesktopMode.Control,
                quality = RemoteDesktopQuality(maxFps = 30, maxBitrateKbps = 4000),
                client = RemoteDesktopClientInfo(
                    appVersion = "2.3.0",
                    viewport = RemoteDesktopViewport(width = 1200, height = 800),
                ),
            ),
        )

        assertEquals(RemoteDesktopState.AwaitingConsent, created.session.state)
        assertTrue(created.session.isAwaitingConsent)
        assertFalse(created.session.state.isTerminal)
        assertNull(created.media)
        assertEquals("pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8", created.participantId)
        assertEquals("wst_1", created.ws.ticket)
        assertEquals(30, created.session.quality?.maxFps)

        val request = server.takeRequest()
        assertEquals("/api/v2/remote-desktop/sessions", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("conn_x", body.getString("connectorId"))
        assertEquals("control", body.getString("mode"))
        // The create request is strict: absent optional values must not be serialized.
        assertFalse(body.has("displayId"))
        assertFalse(body.has("link"))
        assertEquals(30, body.getJSONObject("quality").getInt("maxFps"))
        assertEquals("android", body.getJSONObject("client").getString("platform"))
    }

    @Test
    fun issuedGrantParsesIdentityAndTokenExpiry() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"session":{"id":"rds_01JABCDEF","connectorId":"conn_x","mode":"view","state":"live",
                  "quality":{},"viewerCount":2,"createdAt":"2026-11-13T00:00:00Z"},
                 "participantId":"pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
                 "media":{"provider":"livekit","url":"wss://rtc.example.com","room":"rd_rds_01JABCDEF",
                   "identity":"viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8","token":"eyJ",
                   "tokenExpiresAt":"2026-11-13T00:10:00Z","canPublishData":false},
                 "ws":{"url":"/ws","ticket":"wst_2","expiresAt":"2026-11-13T00:10:00Z"}}
                """.trimIndent(),
            ),
        )

        val created = api.createSession(
            serverUrl,
            "token",
            RemoteDesktopSessionCreateRequest(connectorId = "conn_x", mode = RemoteDesktopMode.View),
        )

        assertEquals(RemoteDesktopMode.View, created.session.mode)
        val media = requireNotNull(created.media) { "Expected a media grant for a live view session." }
        assertEquals("livekit", media.provider)
        assertEquals("rd_rds_01JABCDEF", media.room)
        assertEquals("2026-11-13T00:10:00Z", media.tokenExpiresAt)
        assertEquals(false, media.canPublishData)
    }

    @Test
    fun hostInfoReportsCapabilitiesAndOfflineHost() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"connectorId":"conn_x",
                 "capabilities":[
                   {"capabilityId":"connector.remote_desktop.view","scope":"connector","connectorId":"conn_x",
                    "supported":true,"available":false,"allowed":true,
                    "unavailableReason":"connector_offline","revision":7,"metadata":{}},
                   {"capabilityId":"connector.remote_desktop.control","scope":"connector","connectorId":"conn_x",
                    "supported":true,"available":false,"allowed":false,
                    "unavailableReason":"device_policy_disabled","revision":7,"metadata":{}}],
                 "host":null,"serverTime":"2026-11-13T00:00:00Z"}
                """.trimIndent(),
            ),
        )

        val info = api.getHostInfo(serverUrl, "token", "conn_x")

        assertEquals(2, info.capabilities.size)
        assertNull(info.host)
        val view = info.capabilities.first()
        assertEquals(RemoteDesktopCapabilityId.View, view.capabilityId)
        assertEquals(RemoteDesktopUnavailableReason.ConnectorOffline, view.unavailableReason)
        assertEquals(7L, view.revision)
        assertFalse(view.available)
        assertEquals(
            RemoteDesktopUnavailableReason.DevicePolicyDisabled,
            info.capabilities.last().unavailableReason,
        )
        assertEquals("/api/v2/connectors/conn_x/remote-desktop", server.takeRequest().path)
    }

    @Test
    fun serverErrorsMapToStableCodes() {
        val cases = listOf(
            Triple(409, "remote_desktop_busy", RemoteDesktopErrorCode.Busy),
            Triple(429, "remote_desktop_limit_reached", RemoteDesktopErrorCode.LimitReached),
            Triple(503, "media_unavailable", RemoteDesktopErrorCode.MediaUnavailable),
            Triple(404, "remote_desktop_not_found", RemoteDesktopErrorCode.NotFound),
            Triple(404, "connector_not_found", RemoteDesktopErrorCode.ConnectorNotFound),
            Triple(409, "remote_desktop_consent_pending", RemoteDesktopErrorCode.ConsentPending),
            Triple(409, "remote_desktop_not_controller", RemoteDesktopErrorCode.NotController),
            Triple(429, "remote_desktop_rate_limited", RemoteDesktopErrorCode.RateLimited),
        )

        cases.forEach { (status, code, expected) ->
            server.enqueue(jsonResponse(status, """{"detail":{"code":"$code","message":"$code happened."}}"""))

            val error = failure { api.getSession(serverUrl, "token", "rds_01JABCDEF") }

            assertEquals(expected, error.code)
            assertEquals(code, error.rawCode)
            assertEquals(status, error.statusCode)
            assertEquals("$code happened.", error.message)
        }
    }

    @Test
    fun unavailableDetailsReasonIsPassedThrough() {
        server.enqueue(
            jsonResponse(
                409,
                """
                {"detail":{"code":"remote_desktop_unavailable",
                  "message":"Remote desktop is not available.","details":{"reason":"connector_offline"}}}
                """.trimIndent(),
            ),
        )

        val error = failure {
            api.createSession(
                serverUrl,
                "token",
                RemoteDesktopSessionCreateRequest(connectorId = "conn_x", mode = RemoteDesktopMode.Control),
            )
        }

        assertEquals(RemoteDesktopErrorCode.Unavailable, error.code)
        assertEquals(RemoteDesktopUnavailableReason.ConnectorOffline, error.unavailableReason)
        assertEquals("connector_offline", error.detailsReason)
    }

    @Test
    fun unknownDetailsReasonKeepsItsText() {
        server.enqueue(
            jsonResponse(
                409,
                """
                {"detail":{"code":"remote_desktop_unavailable","message":"Unavailable.",
                  "details":{"reason":"gpu_throttled"}}}
                """.trimIndent(),
            ),
        )

        val error = failure { api.getHostInfo(serverUrl, "token", "conn_x") }

        assertEquals(RemoteDesktopErrorCode.Unavailable, error.code)
        assertEquals(RemoteDesktopUnavailableReason.Unknown, error.unavailableReason)
        assertEquals("gpu_throttled", error.detailsReason)
    }

    @Test
    fun unknownErrorCodeIsPreservedVerbatim() {
        server.enqueue(
            jsonResponse(
                409,
                """{"detail":{"code":"remote_desktop_quarantined","message":"Device quarantined."}}""",
            ),
        )

        val error = failure { api.getSession(serverUrl, "token", "rds_01JABCDEF") }

        assertEquals(RemoteDesktopErrorCode.Unknown, error.code)
        assertEquals("remote_desktop_quarantined", error.rawCode)
        assertEquals("Device quarantined.", error.message)
    }

    @Test
    fun unknownEndReasonDegradesInsteadOfThrowing() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"session":{"id":"rds_01JABCDEF","connectorId":"conn_x","mode":"control","state":"ended",
                  "endReason":"server_pulled_plug","quality":{},"viewerCount":0,
                  "createdAt":"2026-11-13T00:00:00Z","endedAt":"2026-11-13T00:05:00Z"}}
                """.trimIndent(),
            ),
        )
        server.enqueue(
            jsonResponse(
                200,
                """
                {"session":{"id":"rds_01JABCDEG","connectorId":"conn_y","mode":"view","state":"ended",
                  "endReason":"duration_limit","quality":{},"viewerCount":0,
                  "createdAt":"2026-11-13T00:00:00Z","endedAt":"2026-11-13T08:00:00Z"}}
                """.trimIndent(),
            ),
        )

        val futureReason = api.getSession(serverUrl, "token", "rds_01JABCDEF")
        assertEquals(RemoteDesktopState.Ended, futureReason.state)
        assertTrue(futureReason.state.isTerminal)
        assertEquals(RemoteDesktopEndReason.Unknown, futureReason.endReason)
        assertEquals("server_pulled_plug", futureReason.endReasonRaw)
        assertEquals("2026-11-13T00:05:00Z", futureReason.endedAt)

        val knownReason = api.getSession(serverUrl, "token", "rds_01JABCDEG")
        assertEquals(RemoteDesktopEndReason.DurationLimit, knownReason.endReason)
        assertEquals("duration_limit", knownReason.endReasonRaw)
    }

    @Test
    fun tokenRefreshSendsParticipantRoleAndParsesGrant() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"provider":"livekit","url":"wss://rtc.example.com","room":"rd_rds_01JABCDEF",
                 "identity":"viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8","token":"eyJ2",
                 "tokenExpiresAt":"2026-11-13T00:02:00Z","canPublishData":true}
                """.trimIndent(),
            ),
        )

        val grant = api.refreshToken(
            serverUrl = serverUrl,
            authorizationToken = "token",
            sessionId = "rds_01JABCDEF",
            participantId = "pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
            role = RemoteDesktopTokenRole.Controller,
        )

        assertEquals("eyJ2", grant.token)
        assertTrue(grant.canPublishData)
        val request = server.takeRequest()
        assertEquals("/api/v2/remote-desktop/sessions/rds_01JABCDEF/token", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8", body.getString("participantId"))
        assertEquals("controller", body.getString("role"))
    }

    @Test
    fun screenshotBase64Decodes() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"width":1280,"height":720,"capturedAt":"2026-11-13T00:00:00Z","dataBase64":"aGVsbG8="}
                """.trimIndent(),
            ),
        )

        val screenshot = api.screenshot(serverUrl, "token", "rds_01JABCDEF")

        assertEquals(1280, screenshot.width)
        assertEquals(720, screenshot.height)
        assertArrayEquals("hello".toByteArray(), screenshot.bytes())
        val request = server.takeRequest()
        assertEquals("/api/v2/remote-desktop/sessions/rds_01JABCDEF/screenshot", request.path)
        assertEquals("jpeg", JSONObject(request.body.readUtf8()).getString("format"))
    }

    @Test
    fun listSessionsSendsFiltersAndParsesCursor() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"sessions":[{"id":"rds_01JABCDEF","connectorId":"conn_x","mode":"view","state":"live",
                  "quality":{},"viewerCount":1,"createdAt":"2026-11-13T00:00:00Z"}],
                 "nextCursor":"rds_01JABCDEF"}
                """.trimIndent(),
            ),
        )

        val page = api.listSessions(
            serverUrl = serverUrl,
            authorizationToken = "token",
            connectorId = "conn_x",
            state = RemoteDesktopState.Live,
            limit = 50,
            cursor = "rds_01JABCDEF",
        )

        assertEquals(1, page.sessions.size)
        assertEquals("rds_01JABCDEF", page.nextCursor)
        assertEquals(
            "/api/v2/remote-desktop/sessions?connectorId=conn_x&state=live&limit=50&cursor=rds_01JABCDEF",
            server.takeRequest().path,
        )
    }

    private fun jsonResponse(status: Int, body: String): MockResponse = MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun failure(request: () -> Unit): RemoteDesktopException {
        val error = runCatching(request).exceptionOrNull()
        assertTrue("Expected RemoteDesktopException, got $error", error is RemoteDesktopException)
        return error as RemoteDesktopException
    }

    @Test
    fun screenshotParsesTheActualImageFormat() {
        server.enqueue(
            jsonResponse(
                200,
                """
                {"width":640,"height":360,"capturedAt":"2026-11-13T00:00:00Z",
                 "dataBase64":"/9j/4AAQ","format":"jpeg"}
                """.trimIndent(),
            ),
        )
        val jpeg = api.screenshot(serverUrl, "token", "rds_01JABCDEF")
        assertEquals("jpeg", jpeg.format)
        assertEquals("image/jpeg", jpeg.mimeType())
        assertArrayEquals(byteArrayOf(-1, -40, -1, -32, 0, 16), jpeg.bytes())

        // Older hosts omit `format`: treat the bytes as PNG.
        server.enqueue(
            jsonResponse(
                200,
                """
                {"width":640,"height":360,"capturedAt":"2026-11-13T00:00:00Z","dataBase64":"iVBORw0KGgo="}
                """.trimIndent(),
            ),
        )
        val png = api.screenshot(serverUrl, "token", "rds_01JABCDEF")
        assertNull(png.format)
        assertEquals("image/png", png.mimeType())
    }
}
