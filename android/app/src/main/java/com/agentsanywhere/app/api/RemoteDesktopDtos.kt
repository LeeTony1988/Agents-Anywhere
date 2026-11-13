package com.agentsanywhere.app.api

import org.json.JSONObject

// Wire facts live in contracts/remote-desktop/1.0/schemas. Egress is open there:
// unknown enum values and unknown fields must degrade, never throw.

/** `session.state`. Only [Ended] is terminal; anything new falls back to [Unknown]. */
enum class RemoteDesktopState(val wireValue: String) {
    Starting("starting"),
    AwaitingConsent("awaiting_consent"),
    Live("live"),
    Degraded("degraded"),
    Interrupted("interrupted"),
    Ended("ended"),
    Unknown("unknown"),
    ;

    val isTerminal: Boolean
        get() = this == Ended

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopState =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** `session.endReason`, keeping [Unknown] for strings a newer Server may add. */
enum class RemoteDesktopEndReason(val wireValue: String) {
    ClientEnded("client_ended"),
    HostEnded("host_ended"),
    ConsentDenied("consent_denied"),
    ConsentTimeout("consent_timeout"),
    IdleTimeout("idle_timeout"),
    DurationLimit("duration_limit"),
    ConnectorDeleted("connector_deleted"),
    ConnectorOffline("connector_offline"),
    MediaError("media_error"),
    PolicyRevoked("policy_revoked"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopEndReason =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** Why a capability is unavailable; `host.unavailableReason` and `details.reason` share it. */
enum class RemoteDesktopUnavailableReason(val wireValue: String) {
    ConnectorOffline("connector_offline"),
    HostNotRunning("host_not_running"),
    HostNotInstalled("host_not_installed"),
    ConnectorOutdated("connector_outdated"),
    UnsupportedPlatform("unsupported_platform"),
    ScreenPermissionDenied("screen_permission_denied"),
    AccessibilityPermissionDenied("accessibility_permission_denied"),
    DevicePolicyDisabled("device_policy_disabled"),
    SessionLimitReached("session_limit_reached"),
    MediaNotConfigured("media_not_configured"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopUnavailableReason =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

enum class RemoteDesktopCapabilityId(val wireValue: String) {
    View("connector.remote_desktop.view"),
    Control("connector.remote_desktop.control"),
    Clipboard("connector.remote_desktop.clipboard"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopCapabilityId =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

enum class RemoteDesktopMode(val wireValue: String) {
    View("view"),
    Control("control"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopMode =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** `role` of a media token: viewers subscribe, controllers may also publish data. */
enum class RemoteDesktopTokenRole(val wireValue: String) {
    Viewer("viewer"),
    Controller("controller"),
}

enum class RemoteDesktopPermissionState(val wireValue: String) {
    Granted("granted"),
    Denied("denied"),
    NotDetermined("not_determined"),
    Unsupported("unsupported"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopPermissionState =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

enum class RemoteDesktopScreenshotFormat(val wireValue: String) {
    Jpeg("jpeg"),
    Png("png"),
}

/**
 * Stable client-side view of `detail.code`. [Unknown] keeps the Server string in
 * [RemoteDesktopException.rawCode] so a newer error code still shows its own text.
 */
enum class RemoteDesktopErrorCode(val wireValue: String) {
    Unavailable("remote_desktop_unavailable"),
    Busy("remote_desktop_busy"),
    LimitReached("remote_desktop_limit_reached"),
    ConsentPending("remote_desktop_consent_pending"),
    NotController("remote_desktop_not_controller"),
    RateLimited("remote_desktop_rate_limited"),
    NotFound("remote_desktop_not_found"),
    MediaUnavailable("media_unavailable"),
    ConnectorNotFound("connector_not_found"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String?): RemoteDesktopErrorCode =
            entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

/** A remote desktop call the Server rejected; see `error.schema.json`. */
class RemoteDesktopException(
    override val message: String,
    val code: RemoteDesktopErrorCode,
    /** `detail.code` verbatim; null when the Server sent no code at all. */
    val rawCode: String? = null,
    val statusCode: Int? = null,
    /** `details.reason`, when the error carried one (such as `connector_offline`). */
    val unavailableReason: RemoteDesktopUnavailableReason? = null,
    val details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : Exception(message, cause) {
    val detailsReason: String?
        get() = details["reason"] as? String
}

// ---- host info -------------------------------------------------------------

data class RemoteDesktopHostInfo(
    val connectorId: String,
    val capabilities: List<RemoteDesktopCapability>,
    /** Null while the Connector is offline or the host is not installed. */
    val host: RemoteDesktopHostFacts?,
    val serverTime: String?,
)

data class RemoteDesktopCapability(
    val capabilityId: RemoteDesktopCapabilityId,
    /** `capabilityId` verbatim, for ids this build does not know yet. */
    val capabilityIdRaw: String,
    val scope: String,
    val connectorId: String,
    val supported: Boolean,
    val available: Boolean,
    val allowed: Boolean,
    val unavailableReason: RemoteDesktopUnavailableReason?,
    val unavailableReasonRaw: String?,
    val revision: Long,
    val metadata: Map<String, Any?>,
)

data class RemoteDesktopHostFacts(
    val supported: Boolean,
    val available: Boolean,
    val unavailableReason: RemoteDesktopUnavailableReason?,
    val unavailableReasonRaw: String?,
    val platform: String,
    val arch: String,
    val hostVersion: String?,
    val displays: List<RemoteDesktopDisplay>,
    val codecs: List<String>,
    val maxFps: Int,
    val input: RemoteDesktopInputSupport,
    val permissions: RemoteDesktopHostPermissions,
    val activeSessions: Int,
)

data class RemoteDesktopDisplay(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val scale: Double,
    val primary: Boolean,
    val refreshHz: Int?,
    val x: Int?,
    val y: Int?,
)

data class RemoteDesktopInputSupport(
    val pointer: Boolean,
    val keyboard: Boolean,
    val touch: Boolean,
    val clipboard: Boolean,
)

data class RemoteDesktopHostPermissions(
    val screenRecording: RemoteDesktopPermissionState,
    val accessibility: RemoteDesktopPermissionState,
    val inputMonitoring: RemoteDesktopPermissionState,
)

// ---- sessions --------------------------------------------------------------

data class RemoteDesktopSession(
    val id: String,
    val connectorId: String,
    val mode: RemoteDesktopMode,
    val modeRaw: String,
    val displayId: String?,
    val state: RemoteDesktopState,
    val stateRaw: String,
    /** Null until the session ended; [RemoteDesktopEndReason.Unknown] for future reasons. */
    val endReason: RemoteDesktopEndReason?,
    val endReasonRaw: String?,
    /** Full LiveKit identity, such as `viewer:pt_...`, or null without a controller. */
    val controller: String?,
    val controllerLeaseExpiresAt: String?,
    val linkedSessionId: String?,
    /** Reference only; never carries a token. */
    val media: RemoteDesktopMediaReference?,
    val quality: RemoteDesktopQuality?,
    val stats: RemoteDesktopStats?,
    val error: RemoteDesktopSessionError?,
    val viewerCount: Int,
    val createdAt: String,
    val startedAt: String?,
    val endedAt: String?,
) {
    val isAwaitingConsent: Boolean
        get() = state == RemoteDesktopState.AwaitingConsent
}

data class RemoteDesktopMediaReference(
    val provider: String,
    val room: String,
    val url: String,
)

/** Request and response shape of the stream quality knobs. */
data class RemoteDesktopQuality(
    val maxFps: Int? = null,
    val maxBitrateKbps: Int? = null,
    val maxWidth: Int? = null,
    val preferCodec: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        maxFps?.let { put("maxFps", it) }
        maxBitrateKbps?.let { put("maxBitrateKbps", it) }
        maxWidth?.let { put("maxWidth", it) }
        preferCodec?.let { put("preferCodec", it) }
    }
}

data class RemoteDesktopStats(
    val fps: Double,
    val bitrateKbps: Double,
    val rttMs: Double,
    val jitterMs: Double,
    val packetLossPct: Double,
    val codec: String,
    val width: Int,
    val height: Int,
    val encoder: String,
    val subscribers: Int,
)

data class RemoteDesktopSessionError(
    val code: String,
    val message: String,
    val retryable: Boolean,
)

// ---- media grant -----------------------------------------------------------

/** Short-lived LiveKit join grant; only the create response and token refresh return it. */
data class RemoteDesktopMediaGrant(
    val provider: String,
    val url: String,
    val room: String,
    val identity: String,
    val token: String,
    val tokenExpiresAt: String,
    val canPublishData: Boolean,
    val iceServers: List<RemoteDesktopIceServer> = emptyList(),
)

data class RemoteDesktopIceServer(
    val urls: List<String>,
    val username: String?,
    val credential: String?,
)

/** One-time WebSocket ticket; 60 s lifetime. */
data class RemoteDesktopWsTicket(
    val url: String,
    val ticket: String,
    val expiresAt: String,
)

// ---- requests --------------------------------------------------------------

data class RemoteDesktopClientInfo(
    val platform: String = "android",
    val appVersion: String? = null,
    val viewport: RemoteDesktopViewport? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform)
        appVersion?.let { put("appVersion", it) }
        viewport?.let { put("viewport", it.toJson()) }
    }
}

data class RemoteDesktopViewport(
    val width: Int,
    val height: Int,
    val dpr: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("width", width)
        put("height", height)
        dpr?.let { put("dpr", it) }
    }
}

data class RemoteDesktopSessionLink(val sessionId: String) {
    fun toJson(): JSONObject = JSONObject().put("sessionId", sessionId)
}

/** `POST remote-desktop/sessions`; the ingress schema forbids unknown fields, so nulls are omitted. */
data class RemoteDesktopSessionCreateRequest(
    val connectorId: String,
    val mode: RemoteDesktopMode,
    val displayId: String? = null,
    val quality: RemoteDesktopQuality? = null,
    val client: RemoteDesktopClientInfo? = null,
    val link: RemoteDesktopSessionLink? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("connectorId", connectorId)
        put("mode", mode.wireValue)
        displayId?.let { put("displayId", it) }
        quality?.let { put("quality", it.toJson()) }
        client?.let { put("client", it.toJson()) }
        link?.let { put("link", it.toJson()) }
    }
}

data class RemoteDesktopTokenRequest(
    val participantId: String,
    val role: RemoteDesktopTokenRole,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("participantId", participantId)
        put("role", role.wireValue)
    }
}

data class RemoteDesktopTakeControlRequest(val participantId: String) {
    fun toJson(): JSONObject = JSONObject().put("participantId", participantId)
}

/** The release-control body is an empty object; the Server takes the participant from the session. */
object RemoteDesktopReleaseControlRequest {
    fun toJson(): JSONObject = JSONObject()
}

data class RemoteDesktopHeartbeatRequest(val participantId: String) {
    fun toJson(): JSONObject = JSONObject().put("participantId", participantId)
}

data class RemoteDesktopEndRequest(
    val reason: RemoteDesktopEndReason = RemoteDesktopEndReason.ClientEnded,
) {
    fun toJson(): JSONObject = JSONObject().put("reason", reason.wireValue)
}

data class RemoteDesktopTicketRequest(val participantId: String) {
    fun toJson(): JSONObject = JSONObject().put("participantId", participantId)
}

data class RemoteDesktopScreenshotRequest(
    val format: RemoteDesktopScreenshotFormat = RemoteDesktopScreenshotFormat.Jpeg,
    val displayId: String? = null,
    val maxWidth: Int? = 1280,
    val quality: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("format", format.wireValue)
        displayId?.let { put("displayId", it) }
        maxWidth?.let { put("maxWidth", it) }
        quality?.let { put("quality", it) }
    }
}

// ---- responses -------------------------------------------------------------

data class RemoteDesktopSessionCreateResponse(
    val session: RemoteDesktopSession,
    /** Server-generated identity of this client inside the session. */
    val participantId: String,
    /** Null when the session is `awaiting_consent`: no client media token is issued yet. */
    val media: RemoteDesktopMediaGrant?,
    val ws: RemoteDesktopWsTicket,
)

data class RemoteDesktopSessionList(
    val sessions: List<RemoteDesktopSession>,
    val nextCursor: String?,
)

data class RemoteDesktopScreenshot(
    val width: Int,
    val height: Int,
    val capturedAt: String,
    val dataBase64: String,
    /**
     * Actual image type of [dataBase64] (`jpeg`, `png`). The request only hints at
     * a format — the screen host may only be able to encode one of them — so
     * callers must decode by this value; null means PNG (older hosts).
     */
    val format: String? = null,
) {
    fun bytes(): ByteArray = java.util.Base64.getMimeDecoder().decode(dataBase64)

    fun mimeType(): String =
        when (format?.trim()?.lowercase()) {
            "jpeg", "jpg" -> "image/jpeg"
            else -> "image/png"
        }
}

// ---- parsing ---------------------------------------------------------------

internal fun JSONObject.remoteDesktopHostInfo(): RemoteDesktopHostInfo {
    return RemoteDesktopHostInfo(
        connectorId = optString("connectorId"),
        capabilities = optJSONArray("capabilities").toObjectList { remoteDesktopCapability() },
        host = optJSONObject("host")?.remoteDesktopHostFacts(),
        serverTime = optNullableString("serverTime"),
    )
}

internal fun JSONObject.remoteDesktopCapability(): RemoteDesktopCapability {
    val rawId = optString("capabilityId")
    val rawReason = optNullableString("unavailableReason")
    return RemoteDesktopCapability(
        capabilityId = RemoteDesktopCapabilityId.fromWireValue(rawId),
        capabilityIdRaw = rawId,
        scope = optString("scope", "connector").ifBlank { "connector" },
        connectorId = optString("connectorId"),
        supported = optBoolean("supported", false),
        available = optBoolean("available", false),
        allowed = optBoolean("allowed", false),
        unavailableReason = rawReason?.let(RemoteDesktopUnavailableReason::fromWireValue),
        unavailableReasonRaw = rawReason,
        revision = optLong("revision", 0L),
        metadata = optJSONObject("metadata").toMap(),
    )
}

internal fun JSONObject.remoteDesktopHostFacts(): RemoteDesktopHostFacts {
    val rawReason = optNullableString("unavailableReason")
    val permissions = optJSONObject("permissions")
    val input = optJSONObject("input")
    return RemoteDesktopHostFacts(
        supported = optBoolean("supported", false),
        available = optBoolean("available", false),
        unavailableReason = rawReason?.let(RemoteDesktopUnavailableReason::fromWireValue),
        unavailableReasonRaw = rawReason,
        platform = optString("platform", "unknown").ifBlank { "unknown" },
        arch = optString("arch", "unknown").ifBlank { "unknown" },
        hostVersion = optNullableString("hostVersion"),
        displays = optJSONArray("displays").toObjectList { remoteDesktopDisplay() },
        codecs = optJSONArray("codecs").toStringList(),
        maxFps = optInt("maxFps", 0),
        input = RemoteDesktopInputSupport(
            pointer = input?.optBoolean("pointer", false) ?: false,
            keyboard = input?.optBoolean("keyboard", false) ?: false,
            touch = input?.optBoolean("touch", false) ?: false,
            clipboard = input?.optBoolean("clipboard", false) ?: false,
        ),
        permissions = RemoteDesktopHostPermissions(
            screenRecording = RemoteDesktopPermissionState.fromWireValue(permissions?.optString("screenRecording")),
            accessibility = RemoteDesktopPermissionState.fromWireValue(permissions?.optString("accessibility")),
            inputMonitoring = RemoteDesktopPermissionState.fromWireValue(permissions?.optString("inputMonitoring")),
        ),
        activeSessions = optInt("activeSessions", 0),
    )
}

private fun JSONObject.remoteDesktopDisplay(): RemoteDesktopDisplay {
    return RemoteDesktopDisplay(
        id = optString("id"),
        name = optString("name"),
        width = optInt("width", 0),
        height = optInt("height", 0),
        scale = optNullableDouble("scale") ?: 1.0,
        primary = optBoolean("primary", false),
        refreshHz = optNullableInt("refreshHz"),
        x = optNullableInt("x"),
        y = optNullableInt("y"),
    )
}

internal fun JSONObject.remoteDesktopSession(): RemoteDesktopSession {
    val rawState = optString("state")
    val rawMode = optString("mode")
    val rawEndReason = optNullableString("endReason")
    val error = optJSONObject("error")
    return RemoteDesktopSession(
        id = optString("id"),
        connectorId = optString("connectorId"),
        mode = RemoteDesktopMode.fromWireValue(rawMode),
        modeRaw = rawMode,
        displayId = optNullableString("displayId"),
        state = RemoteDesktopState.fromWireValue(rawState),
        stateRaw = rawState,
        endReason = rawEndReason?.let(RemoteDesktopEndReason::fromWireValue),
        endReasonRaw = rawEndReason,
        controller = optNullableString("controller"),
        controllerLeaseExpiresAt = optNullableString("controllerLeaseExpiresAt"),
        linkedSessionId = optNullableString("linkedSessionId"),
        media = optJSONObject("media")?.remoteDesktopMediaReference(),
        quality = optJSONObject("quality")?.remoteDesktopQuality(),
        stats = optJSONObject("stats")?.remoteDesktopStats(),
        error = error?.let {
            RemoteDesktopSessionError(
                code = it.optString("code"),
                message = it.optString("message"),
                retryable = it.optBoolean("retryable", false),
            )
        },
        viewerCount = optInt("viewerCount", 0),
        createdAt = optString("createdAt"),
        startedAt = optNullableString("startedAt"),
        endedAt = optNullableString("endedAt"),
    )
}

private fun JSONObject.remoteDesktopMediaReference(): RemoteDesktopMediaReference {
    return RemoteDesktopMediaReference(
        provider = optString("provider", "livekit").ifBlank { "livekit" },
        room = optString("room"),
        url = optString("url"),
    )
}

private fun JSONObject.remoteDesktopQuality(): RemoteDesktopQuality {
    return RemoteDesktopQuality(
        maxFps = optNullableInt("maxFps"),
        maxBitrateKbps = optNullableInt("maxBitrateKbps"),
        maxWidth = optNullableInt("maxWidth"),
        preferCodec = optNullableString("preferCodec"),
    )
}

internal fun JSONObject.remoteDesktopStats(): RemoteDesktopStats {
    return RemoteDesktopStats(
        fps = optNullableDouble("fps") ?: 0.0,
        bitrateKbps = optNullableDouble("bitrateKbps") ?: 0.0,
        rttMs = optNullableDouble("rttMs") ?: 0.0,
        jitterMs = optNullableDouble("jitterMs") ?: 0.0,
        packetLossPct = optNullableDouble("packetLossPct") ?: 0.0,
        codec = optString("codec"),
        width = optInt("width", 0),
        height = optInt("height", 0),
        encoder = optString("encoder"),
        subscribers = optInt("subscribers", 0),
    )
}

internal fun JSONObject.remoteDesktopMediaGrant(): RemoteDesktopMediaGrant {
    return RemoteDesktopMediaGrant(
        provider = optString("provider", "livekit").ifBlank { "livekit" },
        url = optString("url"),
        room = optString("room"),
        identity = optString("identity"),
        token = optString("token"),
        tokenExpiresAt = optString("tokenExpiresAt"),
        canPublishData = optBoolean("canPublishData", false),
        iceServers = optJSONArray("iceServers").toObjectList { remoteDesktopIceServer() },
    )
}

private fun JSONObject.remoteDesktopIceServer(): RemoteDesktopIceServer {
    return RemoteDesktopIceServer(
        urls = optJSONArray("urls").toStringList(),
        username = optNullableString("username"),
        credential = optNullableString("credential"),
    )
}

internal fun JSONObject.remoteDesktopWsTicket(): RemoteDesktopWsTicket {
    return RemoteDesktopWsTicket(
        url = optString("url"),
        ticket = optString("ticket"),
        expiresAt = optString("expiresAt"),
    )
}

internal fun JSONObject.remoteDesktopSessionList(): RemoteDesktopSessionList {
    return RemoteDesktopSessionList(
        sessions = optJSONArray("sessions").toObjectList { remoteDesktopSession() },
        nextCursor = optNullableString("nextCursor"),
    )
}

internal fun JSONObject.remoteDesktopScreenshot(): RemoteDesktopScreenshot {
    return RemoteDesktopScreenshot(
        width = optInt("width", 0),
        height = optInt("height", 0),
        capturedAt = optString("capturedAt"),
        dataBase64 = optString("dataBase64"),
        format = optNullableString("format"),
    )
}
