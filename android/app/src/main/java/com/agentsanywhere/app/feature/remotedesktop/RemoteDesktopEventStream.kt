package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopQuality
import com.agentsanywhere.app.api.RemoteDesktopSession
import com.agentsanywhere.app.api.RemoteDesktopStats
import com.agentsanywhere.app.api.optNullableString
import com.agentsanywhere.app.api.remoteDesktopSession
import com.agentsanywhere.app.api.remoteDesktopSessionWebSocketUrl
import com.agentsanywhere.app.api.remoteDesktopStats
import com.agentsanywhere.app.api.toMap
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client side of the remote desktop control plane:
 * `WS .../remote-desktop/sessions/{sessionId}/ws?ticket=...&cursor=...`.
 *
 * Only control-plane frames travel here (`client-ws-message.schema.json`); media and input
 * use the LiveKit room. The stream never touches Compose or LiveKit: a socket factory and a
 * [RemoteDesktopScheduler] are injected, so reconnect behaviour is testable.
 */
class RemoteDesktopEventStream(
    private val sockets: RemoteDesktopSocketFactory = OkHttpRemoteDesktopSockets(),
    private val scheduler: RemoteDesktopScheduler = ThreadRemoteDesktopScheduler("remote-desktop-ws"),
    private val clock: () -> Long = { System.currentTimeMillis() },
) : RemoteDesktopControlStream {

    /** Receives every parsed event; unknown `type` values never reach it. */
    fun interface Listener {
        fun onEvent(event: RemoteDesktopServerEvent)
    }

    /**
     * Highest event cursor the Server already delivered (`remote_desktop.subscribed`).
     * A reconnect sends it back so the Server can replay the gap from its ring buffer.
     */
    override var cursor: String? = null
        private set

    private var request: RemoteDesktopStreamRequest? = null
    private var listener: Listener? = null
    private var socket: RemoteDesktopSocket? = null
    private var pendingReconnect: RemoteDesktopCancellable? = null
    private var reconnectAttempt = 0
    private var closed = true

    override fun open(request: RemoteDesktopStreamRequest, listener: Listener) {
        close()
        this.request = request
        this.listener = listener
        this.cursor = null
        this.closed = false
        this.reconnectAttempt = 0
        connect(request.ticket)
    }

    override fun close() {
        closed = true
        pendingReconnect?.cancel()
        pendingReconnect = null
        socket?.close()
        socket = null
        request = null
        listener = null
        cursor = null
        reconnectAttempt = 0
    }

    override fun ping(at: Long): Boolean = send(
        JSONObject().put("type", "ping").put("at", at.coerceAtLeast(0L)),
    )

    override fun requestKeyframe(): Boolean = send(JSONObject().put("type", "request_keyframe"))

    /**
     * `update_quality` only carries the fields the Server schema allows; `preferCodec` is
     * part of the session quality object but is not valid on this frame, so it is dropped.
     */
    override fun updateQuality(quality: RemoteDesktopQuality): Boolean = send(
        JSONObject().put("type", "update_quality").apply {
            quality.maxFps?.let { put("maxFps", it) }
            quality.maxBitrateKbps?.let { put("maxBitrateKbps", it) }
            quality.maxWidth?.let { put("maxWidth", it) }
        },
    )

    private fun send(frame: JSONObject): Boolean = socket?.send(frame.toString()) ?: false

    private fun connect(ticket: String) {
        val request = request ?: return
        val url = remoteDesktopSessionWebSocketUrl(
            serverUrl = request.serverUrl,
            sessionId = request.sessionId,
            ticket = ticket,
            cursor = cursor,
        )
        socket = sockets.open(url, socketListener)
    }

    private fun scheduleReconnect() {
        if (closed) return
        val refreshTicket = request?.refreshTicket ?: return
        val delayMillis = reconnectDelayMillis(reconnectAttempt++)
        pendingReconnect = scheduler.schedule(delayMillis) {
            if (closed) return@schedule
            val ticket = runCatching { refreshTicket() }.getOrNull()
            if (ticket.isNullOrBlank()) {
                scheduleReconnect()
            } else {
                connect(ticket)
            }
        }
    }

    private val socketListener = object : RemoteDesktopSocketListener {
        override fun onOpen() {
            reconnectAttempt = 0
        }

        override fun onText(text: String) {
            val event = RemoteDesktopEventParser.parse(text) ?: return
            if (event is RemoteDesktopServerEvent.Subscribed) {
                cursor = event.cursor.takeIf { it.isNotBlank() } ?: cursor
            }
            listener?.onEvent(event)
        }

        override fun onClosed(code: Int, reason: String?) {
            scheduleReconnect()
        }

        override fun onFailure(error: Throwable) {
            scheduleReconnect()
        }
    }
}

/** Everything one control-plane socket needs; [ticket] must be freshly issued. */
data class RemoteDesktopStreamRequest(
    val serverUrl: String,
    val sessionId: String,
    val ticket: String,
    /**
     * Issues a one-time ticket for a reconnect (the ticket from the create response is
     * valid for 60 s and single use). Null disables automatic reconnects.
     */
    val refreshTicket: (() -> String?)? = null,
)

/**
 * The controller depends on this seam instead of [RemoteDesktopEventStream], so unit tests
 * can drive events without a socket.
 */
interface RemoteDesktopControlStream {
    val cursor: String?

    fun open(request: RemoteDesktopStreamRequest, listener: RemoteDesktopEventStream.Listener)

    fun close()

    fun ping(at: Long): Boolean

    fun requestKeyframe(): Boolean

    fun updateQuality(quality: RemoteDesktopQuality): Boolean
}

/** Minimal socket seam over okhttp, small enough to fake in a unit test. */
interface RemoteDesktopSocket {
    fun send(text: String): Boolean

    fun close(code: Int = NORMAL_CLOSURE_CODE, reason: String? = null)
}

interface RemoteDesktopSocketListener {
    fun onOpen()

    fun onText(text: String)

    fun onClosed(code: Int, reason: String?)

    fun onFailure(error: Throwable)
}

fun interface RemoteDesktopSocketFactory {
    fun open(url: String, listener: RemoteDesktopSocketListener): RemoteDesktopSocket
}

const val NORMAL_CLOSURE_CODE: Int = 1000

/** First reconnect delay; doubles per failed attempt up to [REMOTE_DESKTOP_RECONNECT_MAX_MILLIS]. */
const val REMOTE_DESKTOP_RECONNECT_BASE_MILLIS: Long = 1_000L

/** Reconnects stop backing off here; the Server keeps a 90 s interrupted window. */
const val REMOTE_DESKTOP_RECONNECT_MAX_MILLIS: Long = 15_000L

internal fun reconnectDelayMillis(attempt: Int): Long {
    if (attempt <= 0) return REMOTE_DESKTOP_RECONNECT_BASE_MILLIS
    val capped = attempt.coerceAtMost(4)
    return (REMOTE_DESKTOP_RECONNECT_BASE_MILLIS shl capped).coerceAtMost(REMOTE_DESKTOP_RECONNECT_MAX_MILLIS)
}

/** Production socket factory: okhttp's WebSocket client, no polling and no SSE. */
class OkHttpRemoteDesktopSockets(
    private val client: OkHttpClient = DEFAULT_SOCKET_CLIENT,
) : RemoteDesktopSocketFactory {
    override fun open(url: String, listener: RemoteDesktopSocketListener): RemoteDesktopSocket {
        val webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    listener.onOpen()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    listener.onText(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE_CODE, null)
                    listener.onClosed(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    listener.onClosed(code, reason)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    listener.onFailure(t)
                }
            },
        )
        return object : RemoteDesktopSocket {
            override fun send(text: String): Boolean = webSocket.send(text)

            override fun close(code: Int, reason: String?) {
                webSocket.close(code, reason)
            }
        }
    }

    companion object {
        private val DEFAULT_SOCKET_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}

/** `control.revoked` reason (`realtime-event.schema.json`). */
enum class RemoteDesktopControlRevokedReason(val wireValue: String) {
    TakenOver("taken_over"),
    LeaseExpired("lease_expired"),
    PolicyRevoked("policy_revoked"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopControlRevokedReason =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/**
 * One Server -> client event of the remote desktop WebSocket. Every payload repeats
 * `sessionId`; an event without it cannot be routed and is dropped by the parser.
 */
sealed interface RemoteDesktopServerEvent {
    val sessionId: String

    /** First frame after a (re)connect: [cursor] is the highest event the Server has sent. */
    data class Subscribed(
        override val sessionId: String,
        val cursor: String,
    ) : RemoteDesktopServerEvent

    /** Authoritative session snapshot; drives consent, live and end transitions. */
    data class SessionUpdated(
        override val sessionId: String,
        val session: RemoteDesktopSession,
    ) : RemoteDesktopServerEvent

    data class StatsUpdated(
        override val sessionId: String,
        val stats: RemoteDesktopStats,
        val at: String? = null,
    ) : RemoteDesktopServerEvent

    data class ControlRevoked(
        override val sessionId: String,
        val reason: RemoteDesktopControlRevokedReason,
        val reasonRaw: String,
    ) : RemoteDesktopServerEvent

    data class Ended(
        override val sessionId: String,
        val endReason: RemoteDesktopEndReason,
        val endReasonRaw: String,
        val endedAt: String? = null,
    ) : RemoteDesktopServerEvent

    data class Error(
        override val sessionId: String,
        val code: RemoteDesktopErrorCode,
        val rawCode: String,
        val message: String,
        val retryable: Boolean,
        val details: Map<String, Any?> = emptyMap(),
    ) : RemoteDesktopServerEvent
}

/**
 * Parses the six Server events of `realtime-event.schema.json`. Pure JVM code, so the
 * fixtures under `contracts/remote-desktop/1.0/fixtures/valid/realtime-event/` can be
 * replayed in a unit test.
 *
 * Anything that is not a routable event returns null: malformed JSON, a missing
 * `type`/`payload`/`sessionId`, or a `type` this build does not know yet. Unknown types
 * are ignored on purpose so a newer Server can add events without breaking old clients.
 */
object RemoteDesktopEventParser {
    fun parse(text: String): RemoteDesktopServerEvent? {
        val envelope = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val type = envelope.optString("type").takeIf { it.isNotBlank() } ?: return null
        val payload = envelope.optJSONObject("payload") ?: return null
        val sessionId = payload.optNullableString("sessionId") ?: return null
        return when (type) {
            TYPE_SUBSCRIBED -> RemoteDesktopServerEvent.Subscribed(
                sessionId = sessionId,
                cursor = payload.optString("cursor", ""),
            )

            TYPE_SESSION_UPDATED -> payload.optJSONObject("session")?.let { session ->
                RemoteDesktopServerEvent.SessionUpdated(
                    sessionId = sessionId,
                    session = session.remoteDesktopSession(),
                )
            }

            TYPE_STATS_UPDATED -> payload.optJSONObject("stats")?.let { stats ->
                RemoteDesktopServerEvent.StatsUpdated(
                    sessionId = sessionId,
                    stats = stats.remoteDesktopStats(),
                    at = payload.optNullableString("at"),
                )
            }

            TYPE_CONTROL_REVOKED -> {
                val reasonRaw = payload.optString("reason")
                RemoteDesktopServerEvent.ControlRevoked(
                    sessionId = sessionId,
                    reason = RemoteDesktopControlRevokedReason.fromWireValue(reasonRaw),
                    reasonRaw = reasonRaw,
                )
            }

            TYPE_ENDED -> {
                val reasonRaw = payload.optString("endReason")
                RemoteDesktopServerEvent.Ended(
                    sessionId = sessionId,
                    endReason = RemoteDesktopEndReason.fromWireValue(reasonRaw),
                    endReasonRaw = reasonRaw,
                    endedAt = payload.optNullableString("endedAt"),
                )
            }

            TYPE_ERROR -> {
                val rawCode = payload.optString("code")
                RemoteDesktopServerEvent.Error(
                    sessionId = sessionId,
                    code = RemoteDesktopErrorCode.fromWireValue(rawCode),
                    rawCode = rawCode,
                    message = payload.optString("message"),
                    retryable = payload.optBoolean("retryable", false),
                    details = payload.optJSONObject("details")?.toMap().orEmpty(),
                )
            }

            else -> null
        }
    }

    private const val TYPE_SUBSCRIBED = "remote_desktop.subscribed"
    private const val TYPE_SESSION_UPDATED = "remote_desktop.session.updated"
    private const val TYPE_STATS_UPDATED = "remote_desktop.stats.updated"
    private const val TYPE_CONTROL_REVOKED = "remote_desktop.control.revoked"
    private const val TYPE_ENDED = "remote_desktop.ended"
    private const val TYPE_ERROR = "remote_desktop.error"
}
