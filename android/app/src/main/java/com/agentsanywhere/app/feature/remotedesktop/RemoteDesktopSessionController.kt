package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopApi
import com.agentsanywhere.app.api.RemoteDesktopClientInfo
import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopException
import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopQuality
import com.agentsanywhere.app.api.RemoteDesktopSession
import com.agentsanywhere.app.api.RemoteDesktopSessionCreateRequest
import com.agentsanywhere.app.api.RemoteDesktopState
import com.agentsanywhere.app.api.RemoteDesktopStats
import com.agentsanywhere.app.api.RemoteDesktopTokenRole
import com.agentsanywhere.app.feature.remotedesktop.input.RELEASE_REASON_HANDOFF
import com.agentsanywhere.app.feature.remotedesktop.input.RELEASE_REASON_MANUAL
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputEncoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Controller lease is 60 s; the client renews it every 20 s (`docs/api/remote-desktop.md`). */
const val REMOTE_DESKTOP_HEARTBEAT_MILLIS: Long = 20_000L

/**
 * Floor for the token renewal timer. [tokenRefreshDelayMillis] returns 0 for a token that is
 * already inside the 120 s threshold, and renewing in a tight loop when the Server hands out
 * short tokens would be worse than renewing a little late.
 */
const val REMOTE_DESKTOP_TOKEN_REFRESH_MIN_DELAY_MILLIS: Long = 30_000L

/**
 * Lifecycle of one remote desktop session on the client.
 *
 * The states mirror the Server machine in `docs/api/remote-desktop.md` but stay client-side:
 * [Creating] is the create call, [AwaitingConsent] waits for the device owner to confirm and
 * must not ask for media, [ConnectingMedia] joins the room, [Live] is publishing, and
 * [Reconnecting] covers a media ICE restart or an interrupted session.
 */
sealed interface RemoteDesktopSessionState {
    data object Idle : RemoteDesktopSessionState

    /** `POST remote-desktop/sessions` in flight. */
    data object Creating : RemoteDesktopSessionState

    /** `awaiting_consent`: no media token is issued yet, so nothing is joined. */
    data class AwaitingConsent(val session: RemoteDesktopSession) : RemoteDesktopSessionState

    /** Media token requested and the room is being joined. */
    data class ConnectingMedia(val session: RemoteDesktopSession) : RemoteDesktopSessionState

    /** Joined; [controlling] tells whether this client may publish input. */
    data class Live(
        val session: RemoteDesktopSession,
        val controlling: Boolean,
    ) : RemoteDesktopSessionState

    /** Media or session interrupted; the Server keeps a 90 s reconnect window. */
    data class Reconnecting(
        val session: RemoteDesktopSession?,
        val reason: String? = null,
    ) : RemoteDesktopSessionState

    /** Terminal: [reason] is the contract `endReason`, [reasonRaw] keeps unknown values. */
    data class Ended(
        val session: RemoteDesktopSession?,
        val reason: RemoteDesktopEndReason,
        val reasonRaw: String? = null,
    ) : RemoteDesktopSessionState

    /** Terminal for this attempt; [code] is the stable error code, [rawCode] the wire value. */
    data class Failed(
        val message: String,
        val code: RemoteDesktopErrorCode,
        val rawCode: String? = null,
        val retryable: Boolean = false,
        val detailsReason: String? = null,
    ) : RemoteDesktopSessionState
}

/**
 * Drives one remote desktop session: create/join, consent, media join, heartbeat, token
 * renewal, control handover and teardown.
 *
 * Everything that touches the network or a clock is injected — [RemoteDesktopApi], a
 * [RemoteDesktopControlStream] factory, a [MediaSession], a clock, a scheduler and a
 * coroutine scope — so the lifecycle can be unit tested by advancing fake time. No Compose
 * and no LiveKit types appear here; the LiveKit [MediaSession] and the UI arrive later.
 *
 * `start` is `suspend` because joining the room is; the rest of the public API is either
 * synchronous (`updateQuality`, `requestKeyframe`, `close`) or `suspend` where it calls the
 * Server (`takeControl`, `releaseControl`, `end`).
 */
class RemoteDesktopSessionController(
    private val media: MediaSession,
    private val serverUrl: String,
    private val authorizationToken: String,
    private val api: RemoteDesktopApi = RemoteDesktopApi(),
    private val eventStreams: () -> RemoteDesktopControlStream = { RemoteDesktopEventStream() },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val scheduler: RemoteDesktopScheduler = ThreadRemoteDesktopScheduler(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val _state = MutableStateFlow<RemoteDesktopSessionState>(RemoteDesktopSessionState.Idle)
    val state: StateFlow<RemoteDesktopSessionState> = _state.asStateFlow()

    /** Latest Server `stats.updated` sample, for the diagnostics overlay. */
    private val _stats = MutableStateFlow<RemoteDesktopStats?>(null)
    val stats: StateFlow<RemoteDesktopStats?> = _stats.asStateFlow()

    /** Size of the subscribed video track, once the media layer reports it. */
    private val _trackSize = MutableStateFlow<RemoteDesktopTrackSize?>(null)
    val trackSize: StateFlow<RemoteDesktopTrackSize?> = _trackSize.asStateFlow()

    private val inputEncoder = RemoteInputEncoder()

    private var currentSession: RemoteDesktopSession? = null
    private var participantId: String? = null
    private var requestedMode: RemoteDesktopMode = RemoteDesktopMode.View
    private var requestedQuality: RemoteDesktopQuality? = null
    private var grant: RemoteDesktopMediaGrant? = null
    private var stream: RemoteDesktopControlStream? = null
    private var heartbeatTimer: RemoteDesktopCancellable? = null
    private var tokenTimer: RemoteDesktopCancellable? = null
    private var mediaEventsJob: Job? = null
    private var mediaConnected = false
    private var controlling = false

    /** Current session snapshot, if the create call succeeded. */
    val session: RemoteDesktopSession?
        get() = currentSession

    /** Server-issued id of this client inside the session; every request repeats it. */
    val participant: String?
        get() = participantId

    /** LiveKit identity this client joined with; only known once a media grant arrived. */
    val mediaIdentity: String?
        get() = grant?.identity

    /** True while this client owns control and may publish input. */
    val isControlling: Boolean
        get() = controlling

    /**
     * Creates or joins the session, then follows it: a control session that needs on-device
     * consent stops at [RemoteDesktopSessionState.AwaitingConsent] without asking for media,
     * a `live`/`starting` session requests a token and joins the room.
     */
    suspend fun start(
        connectorId: String,
        mode: RemoteDesktopMode,
        displayId: String? = null,
        quality: RemoteDesktopQuality? = null,
        client: RemoteDesktopClientInfo? = null,
    ) {
        if (isInFlight()) return
        requestedMode = mode
        requestedQuality = quality
        currentSession = null
        participantId = null
        grant = null
        controlling = false
        mediaConnected = false
        _trackSize.value = null
        _stats.value = null
        _state.value = RemoteDesktopSessionState.Creating

        val created = try {
            api.createSession(
                serverUrl = serverUrl,
                authorizationToken = authorizationToken,
                request = RemoteDesktopSessionCreateRequest(
                    connectorId = connectorId,
                    mode = mode,
                    displayId = displayId,
                    quality = quality,
                    client = client,
                ),
            )
        } catch (error: Exception) {
            // RemoteDesktopException keeps its stable code; anything else becomes Unknown.
            _state.value = error.toFailedState()
            return
        }

        participantId = created.participantId
        startMediaEvents()
        openControlStream(sessionId = created.session.id, ticket = created.ws.ticket)
        applyServerSession(created.session, createdGrant = created.media)
    }

    /** Takes over control; while the host waits for consent the session is returned unchanged. */
    suspend fun takeControl() {
        val sessionId = currentSession?.id ?: return
        val participant = participantId ?: return
        val updated = try {
            api.takeControl(serverUrl, authorizationToken, sessionId, participant)
        } catch (error: Exception) {
            // RemoteDesktopException keeps its stable code; anything else becomes Unknown.
            _state.value = error.toFailedState()
            return
        }
        applyServerSession(updated)
    }

    /** Drops control but keeps watching; the host releases every stuck key. */
    suspend fun releaseControl() {
        val sessionId = currentSession?.id ?: return
        val updated = try {
            api.releaseControl(serverUrl, authorizationToken, sessionId)
        } catch (error: Exception) {
            // RemoteDesktopException keeps its stable code; anything else becomes Unknown.
            _state.value = error.toFailedState()
            return
        }
        controlling = false
        stopHeartbeat()
        releaseInput(RELEASE_REASON_MANUAL)
        applyServerSession(updated)
    }

    /** Forwards new stream quality knobs on the control socket; no reply is expected. */
    fun updateQuality(quality: RemoteDesktopQuality) {
        requestedQuality = quality
        stream?.updateQuality(quality)
    }

    /** Asks the host for a keyframe after a stall, a rotation or a foreground return. */
    fun requestKeyframe() {
        stream?.requestKeyframe()
    }

    /** Ends the session on the Server, then releases media and timers. Idempotent. */
    suspend fun end(reason: RemoteDesktopEndReason = RemoteDesktopEndReason.ClientEnded) {
        val sessionId = currentSession?.id
        if (sessionId == null) {
            close()
            return
        }
        releaseInput(RELEASE_REASON_MANUAL)
        val ended = try {
            api.endSession(serverUrl, authorizationToken, sessionId, reason)
        } catch (error: Exception) {
            null
        }
        finishEnded(
            reason = ended?.endReason ?: reason,
            reasonRaw = ended?.endReasonRaw,
            base = ended ?: currentSession,
            endedAt = ended?.endedAt,
        )
    }

    /** Full local teardown without calling the Server; safe to call twice. */
    fun close() {
        stopTimers()
        mediaEventsJob?.cancel()
        mediaEventsJob = null
        stream?.close()
        stream = null
        if (mediaConnected) runCatching { media.disconnect() }
        mediaConnected = false
        controlling = false
        currentSession = null
        participantId = null
        grant = null
        _trackSize.value = null
        _stats.value = null
        _state.value = RemoteDesktopSessionState.Idle
    }

    /** Media events can also be pushed by the caller when it owns the collection. */
    fun onMediaEvent(event: MediaSessionEvent) {
        when (event) {
            MediaSessionEvent.Connected -> {
                val session = currentSession ?: return
                // A stale Connected frame must not bypass the consent step.
                if (session.state.isTerminal || session.state == RemoteDesktopState.AwaitingConsent) return
                _state.value = RemoteDesktopSessionState.Live(session, controlling)
                syncHeartbeat()
            }

            is MediaSessionEvent.TrackAvailable -> {
                _trackSize.value = RemoteDesktopTrackSize(event.width, event.height)
            }

            MediaSessionEvent.Disconnected -> {
                val session = currentSession ?: return
                if (session.state.isTerminal) return
                _state.value = RemoteDesktopSessionState.Reconnecting(session, MEDIA_DISCONNECTED_REASON)
            }

            is MediaSessionEvent.Reconnecting -> {
                val session = currentSession ?: return
                if (session.state.isTerminal) return
                _state.value = RemoteDesktopSessionState.Reconnecting(session, event.reason)
            }
        }
    }

    // ---- session follow-up -------------------------------------------------

    /** True while a create/join is already running, so a second `start` is ignored. */
    private fun isInFlight(): Boolean = when (_state.value) {
        RemoteDesktopSessionState.Creating,
        is RemoteDesktopSessionState.AwaitingConsent,
        is RemoteDesktopSessionState.ConnectingMedia,
        is RemoteDesktopSessionState.Live,
        -> true

        else -> false
    }

    private suspend fun applyServerSession(
        updated: RemoteDesktopSession,
        createdGrant: RemoteDesktopMediaGrant? = null,
    ) {
        currentSession = updated
        when (updated.state) {
            RemoteDesktopState.AwaitingConsent -> {
                // The host has not confirmed yet: no token is issued and no room is joined.
                stopHeartbeat()
                _state.value = RemoteDesktopSessionState.AwaitingConsent(updated)
            }

            RemoteDesktopState.Live, RemoteDesktopState.Degraded -> {
                if (!mediaConnected) {
                    connectMedia(updated, createdGrant)
                } else {
                    controlling = isController(updated)
                    stopHeartbeatIfNotControlling()
                    _state.value = RemoteDesktopSessionState.Live(updated, controlling)
                    syncHeartbeat()
                }
            }

            RemoteDesktopState.Ended -> finishEnded(
                reason = updated.endReason ?: RemoteDesktopEndReason.Unknown,
                reasonRaw = updated.endReasonRaw,
                base = updated,
                endedAt = updated.endedAt,
            )

            else -> {
                // starting / interrupted: the host may not be publishing yet, but a grant from
                // the create response still lets us join early and show the first frame fast.
                stopHeartbeat()
                if (!mediaConnected && createdGrant != null) {
                    connectMedia(updated, createdGrant)
                } else {
                    _state.value = if (mediaConnected) {
                        RemoteDesktopSessionState.Reconnecting(updated, "session_${updated.stateRaw}")
                    } else {
                        RemoteDesktopSessionState.ConnectingMedia(updated)
                    }
                }
            }
        }
    }

    private suspend fun connectMedia(updated: RemoteDesktopSession, createdGrant: RemoteDesktopMediaGrant?) {
        _state.value = RemoteDesktopSessionState.ConnectingMedia(updated)
        val resolved = createdGrant ?: try {
            requestGrant(updated.id, preferController = requestedMode == RemoteDesktopMode.Control)
        } catch (error: Exception) {
            // RemoteDesktopException keeps its stable code; anything else becomes Unknown.
            _state.value = error.toFailedState()
            return
        }
        grant = resolved
        try {
            media.connect(resolved)
        } catch (error: Exception) {
            _state.value = error.toFailedState()
            return
        }
        mediaConnected = true
        controlling = isController(updated, resolved)
        if (updated.state == RemoteDesktopState.Live || updated.state == RemoteDesktopState.Degraded) {
            _state.value = RemoteDesktopSessionState.Live(updated, controlling)
            syncHeartbeat()
        } else {
            // Joined, but the host is not publishing yet: stay in ConnectingMedia until it is.
            _state.value = RemoteDesktopSessionState.ConnectingMedia(updated)
        }
        scheduleTokenRefresh()
    }

    /**
     * A control mode join asks for a controller grant so the host accepts input. When this
     * client is not the controller the Server answers `remote_desktop_not_controller`, and a
     * viewer grant still lets us watch.
     */
    private suspend fun requestGrant(sessionId: String, preferController: Boolean): RemoteDesktopMediaGrant {
        val participant = participantId ?: error("Created the session without a participantId")
        val role = if (preferController) RemoteDesktopTokenRole.Controller else RemoteDesktopTokenRole.Viewer
        return try {
            api.refreshToken(serverUrl, authorizationToken, sessionId, participant, role)
        } catch (error: RemoteDesktopException) {
            if (role == RemoteDesktopTokenRole.Controller) {
                api.refreshToken(serverUrl, authorizationToken, sessionId, participant, RemoteDesktopTokenRole.Viewer)
            } else {
                throw error
            }
        }
    }

    private fun isController(session: RemoteDesktopSession, grant: RemoteDesktopMediaGrant? = this.grant): Boolean {
        val controller = session.controller ?: return false
        return grant?.identity == controller
    }

    // ---- control-plane events ----------------------------------------------

    private fun openControlStream(sessionId: String, ticket: String) {
        val stream = eventStreams().also { this.stream = it }
        stream.open(
            request = RemoteDesktopStreamRequest(
                serverUrl = serverUrl,
                sessionId = sessionId,
                ticket = ticket,
                refreshTicket = {
                    runCatching {
                        api.issueTicket(
                            serverUrl = serverUrl,
                            authorizationToken = authorizationToken,
                            sessionId = sessionId,
                            participantId = participantId.orEmpty(),
                        ).ticket
                    }.getOrNull()
                },
            ),
            listener = RemoteDesktopEventStream.Listener { event -> onServerEvent(event) },
        )
    }

    private fun onServerEvent(event: RemoteDesktopServerEvent) {
        when (event) {
            is RemoteDesktopServerEvent.Subscribed -> Unit // the stream keeps the cursor

            is RemoteDesktopServerEvent.SessionUpdated -> scope.launch { applyServerSession(event.session) }

            is RemoteDesktopServerEvent.StatsUpdated -> _stats.value = event.stats

            is RemoteDesktopServerEvent.ControlRevoked -> onControlRevoked(event)

            is RemoteDesktopServerEvent.Ended -> finishEnded(
                reason = event.endReason,
                reasonRaw = event.endReasonRaw,
                base = currentSession,
                endedAt = event.endedAt,
            )

            is RemoteDesktopServerEvent.Error -> onServerError(event)
        }
    }

    private fun onControlRevoked(event: RemoteDesktopServerEvent.ControlRevoked) {
        controlling = false
        stopHeartbeat()
        // The host already dropped our input; release every key it still believes is held.
        releaseInput(RELEASE_REASON_HANDOFF)
        val session = currentSession
        _state.value = if (session != null) {
            RemoteDesktopSessionState.Live(session, controlling = false)
        } else {
            RemoteDesktopSessionState.Reconnecting(session = null, reason = event.reasonRaw)
        }
    }

    private fun onServerError(event: RemoteDesktopServerEvent.Error) {
        stopTimers()
        if (!event.retryable && mediaConnected) {
            runCatching { media.disconnect() }
            mediaConnected = false
            stream?.close()
            stream = null
        }
        _state.value = RemoteDesktopSessionState.Failed(
            message = event.message,
            code = event.code,
            rawCode = event.rawCode,
            retryable = event.retryable,
        )
    }

    private fun finishEnded(
        reason: RemoteDesktopEndReason,
        reasonRaw: String?,
        base: RemoteDesktopSession?,
        endedAt: String?,
    ) {
        stopTimers()
        stream?.close()
        stream = null
        if (mediaConnected) runCatching { media.disconnect() }
        mediaConnected = false
        controlling = false
        val endedSession = base?.copy(
            state = RemoteDesktopState.Ended,
            stateRaw = RemoteDesktopState.Ended.wireValue,
            endReason = reason,
            endReasonRaw = reasonRaw,
            endedAt = endedAt ?: base.endedAt,
        )
        currentSession = endedSession
        _state.value = RemoteDesktopSessionState.Ended(endedSession, reason, reasonRaw)
    }

    // ---- timers ------------------------------------------------------------

    private fun syncHeartbeat() {
        val session = currentSession
        val live = session != null &&
            (session.state == RemoteDesktopState.Live || session.state == RemoteDesktopState.Degraded)
        if (live && controlling) startHeartbeat() else stopHeartbeat()
    }

    private fun stopHeartbeatIfNotControlling() {
        if (!controlling) stopHeartbeat()
    }

    private fun startHeartbeat() {
        if (heartbeatTimer != null) return
        scheduleHeartbeat()
    }

    private fun scheduleHeartbeat() {
        heartbeatTimer = scheduler.schedule(REMOTE_DESKTOP_HEARTBEAT_MILLIS) {
            heartbeatTimer = null
            sendHeartbeat()
            val session = currentSession
            val live = session != null && session.state == RemoteDesktopState.Live
            if (live && controlling) scheduleHeartbeat()
        }
    }

    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    private fun sendHeartbeat() {
        val sessionId = currentSession?.id ?: return
        val participant = participantId ?: return
        if (!controlling) return
        val updated = try {
            api.heartbeat(serverUrl, authorizationToken, sessionId, participant)
        } catch (error: RemoteDesktopException) {
            if (error.code == RemoteDesktopErrorCode.NotController) {
                controlling = false
                stopHeartbeat()
                currentSession?.let { _state.value = RemoteDesktopSessionState.Live(it, controlling = false) }
            }
            return
        } catch (error: Exception) {
            return
        }
        if (updated.state == RemoteDesktopState.Ended) {
            finishEnded(
                reason = updated.endReason ?: RemoteDesktopEndReason.Unknown,
                reasonRaw = updated.endReasonRaw,
                base = updated,
                endedAt = updated.endedAt,
            )
        } else {
            currentSession = currentSession?.copy(controllerLeaseExpiresAt = updated.controllerLeaseExpiresAt)
        }
    }

    private fun scheduleTokenRefresh() {
        val grant = grant ?: return
        tokenTimer?.cancel()
        val delayMillis = tokenRefreshDelayMillis(grant, clock())
            .coerceAtLeast(REMOTE_DESKTOP_TOKEN_REFRESH_MIN_DELAY_MILLIS)
        tokenTimer = scheduler.schedule(delayMillis) {
            tokenTimer = null
            scope.launch { renewMediaGrant() }
        }
    }

    private suspend fun renewMediaGrant() {
        val session = currentSession ?: return
        if (session.state.isTerminal) return
        val renewed = try {
            requestGrant(session.id, preferController = controlling)
        } catch (error: RemoteDesktopException) {
            if (!error.isRetryable()) _state.value = error.toFailedState()
            return
        } catch (error: Exception) {
            return
        }
        grant = renewed
        runCatching { media.connect(renewed) }
        scheduleTokenRefresh()
    }

    private fun stopTimers() {
        stopHeartbeat()
        tokenTimer?.cancel()
        tokenTimer = null
    }

    // ---- media plumbing ----------------------------------------------------

    private fun startMediaEvents() {
        if (mediaEventsJob != null) return
        mediaEventsJob = scope.launch {
            media.events.collect { event -> onMediaEvent(event) }
        }
    }

    private fun releaseInput(reason: String) {
        if (!mediaConnected) return
        runCatching { media.sendInput(inputEncoder.releaseAllEvent(reason).toString()) }
    }

    private companion object {
        const val MEDIA_DISCONNECTED_REASON = "media_disconnected"
    }
}

/**
 * A transport failure or a 5xx/429 is worth retrying; a 4xx decision by the Server is not.
 * `RemoteDesktopException` carries the HTTP status instead of a `retryable` flag.
 */
internal fun RemoteDesktopException.isRetryable(): Boolean {
    val status = statusCode ?: return true
    return status == 429 || status >= 500
}

internal fun RemoteDesktopException.toFailedState(): RemoteDesktopSessionState.Failed {
    return RemoteDesktopSessionState.Failed(
        message = message,
        code = code,
        rawCode = rawCode,
        retryable = isRetryable(),
        detailsReason = detailsReason,
    )
}

internal fun Throwable.toFailedState(): RemoteDesktopSessionState.Failed {
    if (this is RemoteDesktopException) return toFailedState()
    return RemoteDesktopSessionState.Failed(
        message = message ?: MEDIA_FAILURE_MESSAGE,
        code = RemoteDesktopErrorCode.Unknown,
        retryable = true,
    )
}

private const val MEDIA_FAILURE_MESSAGE = "Remote desktop failed."
