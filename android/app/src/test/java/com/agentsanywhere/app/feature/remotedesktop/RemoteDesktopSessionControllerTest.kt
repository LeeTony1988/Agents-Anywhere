package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopQuality
import com.agentsanywhere.app.api.RemoteDesktopTokenRole
import com.agentsanywhere.app.api.ApiClient
import com.agentsanywhere.app.api.RemoteDesktopApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the lifecycle rules of `docs/api/remote-desktop.md` with fake media, fake control
 * stream and a fake clock/scheduler; only the HTTP leg is real (MockWebServer), which also
 * proves the controller calls the endpoints the DTO/API layer exposes.
 */
class RemoteDesktopSessionControllerTest {

    private val server = MockWebServer()
    private val clock = FakeClock(START_MILLIS)
    private val scheduler = FakeScheduler(clock)
    private val media = FakeMediaSession()
    private val stub = RemoteDesktopServerStub(clock)
    private val streams = mutableListOf<FakeControlStream>()
    private lateinit var controller: RemoteDesktopSessionController

    @Before
    fun setUp() {
        server.dispatcher = stub
        server.start()
        controller = RemoteDesktopSessionController(
            media = media,
            serverUrl = server.url("/").toString().trimEnd('/'),
            authorizationToken = "token",
            api = RemoteDesktopApi(ApiClient()),
            eventStreams = { FakeControlStream().also { streams += it } },
            clock = { clock.now },
            scheduler = scheduler,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    @After
    fun tearDown() {
        controller.close()
        server.shutdown()
    }

    @Test
    fun controlSessionAwaitingConsentDoesNotConnectMedia() = runBlocking {
        stub.createBody = createBody(state = "awaiting_consent", mediaIdentity = null)

        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        val state = controller.state.value
        assertTrue("Expected AwaitingConsent, got $state", state is RemoteDesktopSessionState.AwaitingConsent)
        assertTrue("No media grant may be requested while consent is pending", media.grants.isEmpty())
        assertEquals(0, stub.tokenCalls.get())
        // The control socket still opens, so the consent result can arrive as an event.
        assertNotNull(streams.single().opened)
    }

    @Test
    fun liveControlSessionJoinsMediaExactlyOnce() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)

        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        val state = controller.state.value as RemoteDesktopSessionState.Live
        assertTrue(state.controlling)
        assertEquals(1, media.grants.size)
        assertEquals(IDENTITY, media.grants.single().identity)

        media.events.tryEmit(MediaSessionEvent.Connected)
        assertEquals("A media reconnect event must not rejoin the room", 1, media.grants.size)
        assertTrue(controller.state.value is RemoteDesktopSessionState.Live)
    }

    @Test
    fun consentGrantedLaterRequestsMediaAndJoins() = runBlocking {
        stub.createBody = createBody(state = "awaiting_consent", mediaIdentity = null)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)
        assertTrue(controller.state.value is RemoteDesktopSessionState.AwaitingConsent)

        streams.single().emit(sessionUpdatedEvent(state = "live", controller = IDENTITY))

        assertEquals("A controller token is requested once consent arrives", 1, stub.tokenCalls.get())
        assertEquals(1, media.grants.size)
        val state = controller.state.value as RemoteDesktopSessionState.Live
        assertTrue(state.controlling)
    }

    @Test
    fun startingSessionWithGrantJoinsEarlyAndBecomesLiveOnTheNextEvent() = runBlocking {
        stub.createBody = createBody(state = "starting", mediaIdentity = IDENTITY, controller = null)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        assertEquals("A starting session still gets a viewer grant", 1, media.grants.size)
        assertTrue(
            "Joined but not live yet, got ${controller.state.value}",
            controller.state.value is RemoteDesktopSessionState.ConnectingMedia,
        )

        streams.single().emit(sessionUpdatedEvent(state = "live", controller = IDENTITY))

        val live = controller.state.value as RemoteDesktopSessionState.Live
        assertTrue(live.controlling)
        assertEquals("The live event must not join the room a second time", 1, media.grants.size)
    }

    @Test
    fun heartbeatRunsEveryTwentySecondsAndStopsOnEnd() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        scheduler.advanceBy(REMOTE_DESKTOP_HEARTBEAT_MILLIS - 1)
        assertEquals(0, stub.heartbeatCalls.get())

        scheduler.advanceBy(1)
        assertEquals(1, stub.heartbeatCalls.get())

        scheduler.advanceBy(REMOTE_DESKTOP_HEARTBEAT_MILLIS)
        assertEquals(2, stub.heartbeatCalls.get())

        controller.end()
        scheduler.advanceBy(REMOTE_DESKTOP_HEARTBEAT_MILLIS * 3)
        assertEquals(2, stub.heartbeatCalls.get())
    }

    @Test
    fun viewerSessionNeverSendsHeartbeats() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = "viewer:someone_else")
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.View)

        val state = controller.state.value as RemoteDesktopSessionState.Live
        assertFalse(state.controlling)

        scheduler.advanceBy(REMOTE_DESKTOP_HEARTBEAT_MILLIS * 2)
        assertEquals(0, stub.heartbeatCalls.get())
    }

    @Test
    fun tokenIsRenewedRightBeforeTheThreshold() = runBlocking {
        // 600 s token, renew with 120 s to spare: the timer fires after 480 s.
        stub.createBody = createBody(
            state = "live",
            mediaIdentity = IDENTITY,
            controller = IDENTITY,
            tokenExpiresAtMillis = START_MILLIS + 600_000L,
        )
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        scheduler.advanceBy(479_000L)
        assertEquals(0, stub.tokenCalls.get())

        scheduler.advanceBy(1_000L)
        assertEquals(1, stub.tokenCalls.get())
        assertEquals("The renewed grant is joined again", 2, media.grants.size)
        assertEquals(RemoteDesktopTokenRole.Controller, stub.tokenRoles.single())
    }

    @Test
    fun controlRevokedDropsControlStopsHeartbeatAndReleasesInput() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)
        val heartbeatsBefore = stub.heartbeatCalls.get()

        streams.single().emit(
            RemoteDesktopServerEvent.ControlRevoked(
                sessionId = SESSION_ID,
                reason = RemoteDesktopControlRevokedReason.TakenOver,
                reasonRaw = "taken_over",
            ),
        )

        val state = controller.state.value as RemoteDesktopSessionState.Live
        assertFalse(state.controlling)
        assertFalse(controller.isControlling)

        scheduler.advanceBy(REMOTE_DESKTOP_HEARTBEAT_MILLIS * 3)
        assertEquals(heartbeatsBefore, stub.heartbeatCalls.get())

        val releaseAll = media.inputs.map { JSONObject(it) }.single { it.getString("t") == "release_all" }
        assertEquals("handoff", releaseAll.getString("reason"))
    }

    @Test
    fun serverErrorBecomesFailedAndKeepsTheCode() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        streams.single().emit(
            RemoteDesktopServerEvent.Error(
                sessionId = SESSION_ID,
                code = RemoteDesktopErrorCode.Unavailable,
                rawCode = "remote_desktop_unavailable",
                message = "Connector is offline",
                retryable = false,
            ),
        )

        val failed = controller.state.value as RemoteDesktopSessionState.Failed
        assertEquals(RemoteDesktopErrorCode.Unavailable, failed.code)
        assertEquals("remote_desktop_unavailable", failed.rawCode)
        assertEquals("Connector is offline", failed.message)
        assertFalse(failed.retryable)
        assertEquals("A non-retryable error releases the room", 1, media.disconnects)
    }

    @Test
    fun sessionEndedEventEndsTheSession() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        streams.single().emit(
            RemoteDesktopServerEvent.Ended(
                sessionId = SESSION_ID,
                endReason = RemoteDesktopEndReason.HostEnded,
                endReasonRaw = "host_ended",
                endedAt = "2026-11-13T00:41:12Z",
            ),
        )

        val ended = controller.state.value as RemoteDesktopSessionState.Ended
        assertEquals(RemoteDesktopEndReason.HostEnded, ended.reason)
        assertEquals("host_ended", ended.reasonRaw)
        assertEquals(1, media.disconnects)
        assertTrue(streams.single().closed)
    }

    @Test
    fun createFailureMapsToFailedWithTheServerCode() = runBlocking {
        stub.createStatus = 409
        stub.createBody = """{"detail":{"code":"remote_desktop_busy","message":"Control is taken."}}"""

        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        val failed = controller.state.value as RemoteDesktopSessionState.Failed
        assertEquals(RemoteDesktopErrorCode.Busy, failed.code)
        assertEquals("remote_desktop_busy", failed.rawCode)
        assertTrue("A 409 is a Server decision, not a transport failure", !failed.retryable)
        assertTrue(media.grants.isEmpty())
        assertTrue(streams.isEmpty())
    }

    @Test
    fun transportFailureMapsToARetryableFailure() = runBlocking {
        stub.createStatus = 503
        stub.createBody = """{"detail":{"code":"media_unavailable","message":"LiveKit is down."}}"""

        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        val failed = controller.state.value as RemoteDesktopSessionState.Failed
        assertEquals(RemoteDesktopErrorCode.MediaUnavailable, failed.code)
        assertTrue(failed.retryable)
    }

    @Test
    fun mediaReconnectAndQualityAreReflected() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        media.events.tryEmit(MediaSessionEvent.TrackAvailable(width = 1920, height = 1080))
        assertEquals(RemoteDesktopTrackSize(1920, 1080), controller.trackSize.value)

        media.events.tryEmit(MediaSessionEvent.Reconnecting("ice_restart"))
        val reconnecting = controller.state.value as RemoteDesktopSessionState.Reconnecting
        assertEquals("ice_restart", reconnecting.reason)

        media.events.tryEmit(MediaSessionEvent.Connected)
        assertTrue(controller.state.value is RemoteDesktopSessionState.Live)

        controller.updateQuality(RemoteDesktopQuality(maxFps = 15))
        controller.requestKeyframe()
        assertEquals(listOf(15), streams.single().qualities.map { it.maxFps })
        assertEquals(1, streams.single().keyframeRequests)
    }

    @Test
    fun endSendsTheEndRequestAndDisconnects() = runBlocking {
        stub.createBody = createBody(state = "live", mediaIdentity = IDENTITY, controller = IDENTITY)
        controller.start(connectorId = "conn_x", mode = RemoteDesktopMode.Control)

        controller.end()

        assertEquals(1, stub.endCalls.get())
        val ended = controller.state.value as RemoteDesktopSessionState.Ended
        assertEquals(RemoteDesktopEndReason.ClientEnded, ended.reason)
        assertEquals(1, media.disconnects)
        assertEquals(0, scheduler.pending)
    }

    private companion object {
        const val SESSION_ID = "rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C"
        const val IDENTITY = "viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8"
        val START_MILLIS: Long = Instant.parse("2026-11-13T00:00:00Z").toEpochMilli()
    }
}

// ---- fixtures --------------------------------------------------------------

private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

private fun jsonResponse(status: Int, body: String): MockResponse = MockResponse()
    .setResponseCode(status)
    .setHeader("Content-Type", "application/json")
    .setBody(body)

internal fun sessionBody(
    state: String,
    controller: String? = null,
    endReason: String? = null,
    leaseExpiresAtMillis: Long? = null,
    stats: Boolean = false,
): String {
    val controllerLiteral = controller?.let { "\"$it\"" } ?: "null"
    val endReasonLiteral = endReason?.let { "\"$it\"" } ?: "null"
    val leaseLiteral = leaseExpiresAtMillis?.let { "\"${iso(it)}\"" } ?: "null"
    val statsLiteral = if (stats) {
        """
        "stats":{"fps":29.7,"bitrateKbps":2610,"rttMs":38,"jitterMs":4.2,"packetLossPct":0.3,
          "codec":"vp8","width":1920,"height":1080,"encoder":"libwebrtc-sw","subscribers":2},
        """.trimIndent()
    } else {
        ""
    }
    return """
        {"id":"rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C","connectorId":"conn_x","mode":"control","displayId":"0",
         "state":"$state","endReason":$endReasonLiteral,"controller":$controllerLiteral,
         "controllerLeaseExpiresAt":$leaseLiteral,
         "media":{"provider":"livekit","room":"rd_rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C","url":"wss://rtc.example.com"},
         "quality":{"maxFps":30,"maxBitrateKbps":4000},$statsLiteral
         "viewerCount":1,"createdAt":"2026-11-13T00:00:00Z"}
    """.trimIndent()
}

private fun createBody(
    state: String,
    mediaIdentity: String?,
    controller: String? = null,
    tokenExpiresAtMillis: Long = 0L,
): String {
    val mediaLiteral = mediaIdentity?.let { identity ->
        """
        {"provider":"livekit","url":"wss://rtc.example.com","room":"rd_rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
         "identity":"$identity","token":"eyJhbGciOi","tokenExpiresAt":"${iso(tokenExpiresAtMillis)}",
         "canPublishData":true}
        """.trimIndent()
    } ?: "null"
    return """
        {"session":${sessionBody(state = state, controller = controller)},
         "participantId":"pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8",
         "media":$mediaLiteral,
         "ws":{"url":"/remote-desktop/sessions/rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C/ws","ticket":"wst_create",
           "expiresAt":"2026-11-13T00:01:00Z"}}
    """.trimIndent()
}

/** Builds a real `session.updated` event through the production parser. */
private fun sessionUpdatedEvent(
    state: String,
    controller: String? = null,
): RemoteDesktopServerEvent.SessionUpdated {
    val text = """
        {"type":"remote_desktop.session.updated",
         "payload":{"sessionId":"rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
           "session":${sessionBody(state = state, controller = controller)}}}
    """.trimIndent()
    return RemoteDesktopEventParser.parse(text) as RemoteDesktopServerEvent.SessionUpdated
}

// ---- fakes -----------------------------------------------------------------

private class FakeClock(var now: Long)

private class FakeScheduler(private val clock: FakeClock) : RemoteDesktopScheduler {
    private class Task(val dueAt: Long, val block: () -> Unit) {
        var cancelled = false
    }

    private val tasks = mutableListOf<Task>()

    val pending: Int
        get() = tasks.count { !it.cancelled }

    override fun schedule(delayMillis: Long, block: () -> Unit): RemoteDesktopCancellable {
        val task = Task(dueAt = clock.now + delayMillis.coerceAtLeast(0L), block = block)
        tasks += task
        return RemoteDesktopCancellable { task.cancelled = true }
    }

    fun advanceBy(millis: Long) = advanceTo(clock.now + millis)

    fun advanceTo(target: Long) {
        while (true) {
            val next = tasks.filter { !it.cancelled && it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
            tasks.remove(next)
            clock.now = maxOf(clock.now, next.dueAt)
            runCatching { next.block() }
        }
        clock.now = maxOf(clock.now, target)
    }
}

private class FakeMediaSession : MediaSession {
    val grants = mutableListOf<RemoteDesktopMediaGrant>()
    val inputs = mutableListOf<String>()
    var disconnects = 0
    private val _events = MutableSharedFlow<MediaSessionEvent>(extraBufferCapacity = 32)

    override val events = _events

    override suspend fun connect(grant: RemoteDesktopMediaGrant) {
        grants += grant
    }

    override fun disconnect() {
        disconnects += 1
    }

    override fun sendInput(json: String) {
        inputs += json
    }

    override fun sendPointerFrame(bytes: ByteArray) = Unit
}

private class FakeControlStream : RemoteDesktopControlStream {
    override var cursor: String? = null
    var opened: RemoteDesktopStreamRequest? = null
    var listener: RemoteDesktopEventStream.Listener? = null
    var closed = false
    val qualities = mutableListOf<RemoteDesktopQuality>()
    var keyframeRequests = 0

    override fun open(request: RemoteDesktopStreamRequest, listener: RemoteDesktopEventStream.Listener) {
        opened = request
        this.listener = listener
        cursor = null
    }

    override fun close() {
        closed = true
        listener = null
    }

    override fun ping(at: Long): Boolean = true

    override fun requestKeyframe(): Boolean {
        keyframeRequests += 1
        return true
    }

    override fun updateQuality(quality: RemoteDesktopQuality): Boolean {
        qualities += quality
        return true
    }

    fun emit(event: RemoteDesktopServerEvent) {
        listener?.onEvent(event)
    }
}

private class RemoteDesktopServerStub(private val clock: FakeClock) : Dispatcher() {
    @Volatile
    var createStatus: Int = 201

    @Volatile
    var createBody: String = ""

    val tokenCalls = AtomicInteger()
    val heartbeatCalls = AtomicInteger()
    val endCalls = AtomicInteger()
    val ticketCalls = AtomicInteger()
    val tokenRoles = java.util.concurrent.CopyOnWriteArrayList<RemoteDesktopTokenRole>()

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path.endsWith("/remote-desktop/sessions") -> jsonResponse(createStatus, createBody)

            path.endsWith("/token") -> {
                tokenCalls.incrementAndGet()
                val body = JSONObject(request.body.readUtf8())
                tokenRoles += if (body.optString("role") == "controller") {
                    RemoteDesktopTokenRole.Controller
                } else {
                    RemoteDesktopTokenRole.Viewer
                }
                jsonResponse(
                    200,
                    """
                    {"provider":"livekit","url":"wss://rtc.example.com",
                     "room":"rd_rds_01J9Z6K8Y3M2Q4T7V5W8X1B0C",
                     "identity":"viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8","token":"eyJyZW5ld2Vk",
                     "tokenExpiresAt":"${iso(clock.now + 600_000L)}","canPublishData":true}
                    """.trimIndent(),
                )
            }

            path.endsWith("/heartbeat") -> {
                heartbeatCalls.incrementAndGet()
                jsonResponse(
                    200,
                    sessionBody(state = "live", controller = "viewer:pt_9f2c1a7b3d4e5f60718293a4b5c6d7e8"),
                )
            }

            path.endsWith("/end") -> {
                endCalls.incrementAndGet()
                jsonResponse(
                    200,
                    sessionBody(state = "ended", endReason = "client_ended"),
                )
            }

            path.endsWith("/ticket") -> {
                ticketCalls.incrementAndGet()
                jsonResponse(
                    200,
                    """{"url":"/ws","ticket":"wst_reconnect","expiresAt":"${iso(clock.now + 60_000L)}"}""",
                )
            }

            else -> jsonResponse(404, """{"detail":{"code":"not_found","message":"No route."}}""")
        }
    }
}
