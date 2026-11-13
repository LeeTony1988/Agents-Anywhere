package com.agentsanywhere.app.api

import org.json.JSONObject

/**
 * Remote desktop v1 endpoints on top of [ApiClient]: the bearer token, the
 * `/api/v2` prefix and the error envelope all come from the client the caller
 * built. Wire facts are `contracts/remote-desktop/1.0/schemas`.
 *
 * Every Server rejection is rethrown as [RemoteDesktopException], which keeps a
 * stable [RemoteDesktopErrorCode] plus the raw code and `details.reason` for
 * errors this build does not know yet.
 */
class RemoteDesktopApi(
    private val client: ApiClient = ApiClient(),
) {
    /** `GET connectors/{id}/remote-desktop`: Server policy capabilities plus the host's own facts. */
    fun getHostInfo(
        serverUrl: String,
        authorizationToken: String,
        connectorId: String,
    ): RemoteDesktopHostInfo = mapped {
        client.getJson(
            serverUrl = serverUrl,
            path = "/connectors/${connectorId.urlEncode()}/remote-desktop",
            authorizationToken = authorizationToken,
        ).remoteDesktopHostInfo()
    }

    /**
     * Create-or-join the Connector's active session. A control session that still
     * needs on-device consent comes back `awaiting_consent` with a null media
     * grant, so no LiveKit token is issued until the user confirms.
     */
    fun createSession(
        serverUrl: String,
        authorizationToken: String,
        request: RemoteDesktopSessionCreateRequest,
    ): RemoteDesktopSessionCreateResponse = mapped {
        val response = client.postJson(
            serverUrl = serverUrl,
            path = "/remote-desktop/sessions",
            body = request.toJson(),
            authorizationToken = authorizationToken,
        )
        RemoteDesktopSessionCreateResponse(
            session = response.sessionObject(),
            participantId = response.optString("participantId"),
            media = response.optJSONObject("media")?.remoteDesktopMediaGrant(),
            ws = response.requireObject("ws").remoteDesktopWsTicket(),
        )
    }

    fun getSession(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
    ): RemoteDesktopSession = mapped {
        client.getJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId),
            authorizationToken = authorizationToken,
        ).sessionObject()
    }

    /** Sessions of one Connector, or every session of the user; newest first. */
    fun listSessions(
        serverUrl: String,
        authorizationToken: String,
        connectorId: String? = null,
        state: RemoteDesktopState? = null,
        limit: Int? = null,
        cursor: String? = null,
    ): RemoteDesktopSessionList = mapped {
        val query = buildList {
            connectorId?.takeIf(String::isNotBlank)?.let { add("connectorId=${it.urlEncode()}") }
            state?.takeIf { it != RemoteDesktopState.Unknown }?.let { add("state=${it.wireValue}") }
            limit?.let { add("limit=$it") }
            cursor?.takeIf(String::isNotBlank)?.let { add("cursor=${it.urlEncode()}") }
        }.joinToString("&")
        val path = "/remote-desktop/sessions" + if (query.isEmpty()) "" else "?$query"
        client.getJson(
            serverUrl = serverUrl,
            path = path,
            authorizationToken = authorizationToken,
        ).remoteDesktopSessionList()
    }

    /**
     * Renew the join grant. Viewer tokens live 600 s and controller tokens 120 s;
     * a controller token is only issued once the host session is live.
     */
    fun refreshToken(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        participantId: String,
        role: RemoteDesktopTokenRole,
    ): RemoteDesktopMediaGrant = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "token"),
            body = RemoteDesktopTokenRequest(participantId, role).toJson(),
            authorizationToken = authorizationToken,
        ).remoteDesktopMediaGrant()
    }

    /** Takes over control; while the host waits for consent the session is returned unchanged. */
    fun takeControl(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        participantId: String,
    ): RemoteDesktopSession = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "take-control"),
            body = RemoteDesktopTakeControlRequest(participantId).toJson(),
            authorizationToken = authorizationToken,
        ).sessionObject()
    }

    fun releaseControl(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
    ): RemoteDesktopSession = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "release-control"),
            body = RemoteDesktopReleaseControlRequest.toJson(),
            authorizationToken = authorizationToken,
        ).sessionObject()
    }

    /** Renews the 60 s controller lease; only the current controller may call it. */
    fun heartbeat(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        participantId: String,
    ): RemoteDesktopSession = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "heartbeat"),
            body = RemoteDesktopHeartbeatRequest(participantId).toJson(),
            authorizationToken = authorizationToken,
        ).sessionObject()
    }

    /** Idempotent: an already ended session is returned unchanged. */
    fun endSession(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        reason: RemoteDesktopEndReason = RemoteDesktopEndReason.ClientEnded,
    ): RemoteDesktopSession = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "end"),
            body = RemoteDesktopEndRequest(reason).toJson(),
            authorizationToken = authorizationToken,
        ).sessionObject()
    }

    /** One-time WebSocket ticket for the session control channel; 60 s lifetime. */
    fun issueTicket(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        participantId: String,
    ): RemoteDesktopWsTicket = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "ticket"),
            body = RemoteDesktopTicketRequest(participantId).toJson(),
            authorizationToken = authorizationToken,
        ).remoteDesktopWsTicket()
    }

    /** Only possible inside a live session; the Server rate limits it to one per 2 s. */
    fun screenshot(
        serverUrl: String,
        authorizationToken: String,
        sessionId: String,
        request: RemoteDesktopScreenshotRequest = RemoteDesktopScreenshotRequest(),
    ): RemoteDesktopScreenshot = mapped {
        client.postJson(
            serverUrl = serverUrl,
            path = sessionPath(sessionId, "screenshot"),
            body = request.toJson(),
            authorizationToken = authorizationToken,
        ).remoteDesktopScreenshot()
    }

    private fun sessionPath(
        sessionId: String,
        action: String? = null,
    ): String {
        val base = "/remote-desktop/sessions/${sessionId.urlEncode()}"
        return if (action == null) base else "$base/$action"
    }

    private fun JSONObject.sessionObject(): RemoteDesktopSession =
        requireObject("session").remoteDesktopSession()

    private fun JSONObject.requireObject(name: String): JSONObject =
        optJSONObject(name) ?: throw ApiException(INVALID_RESPONSE)

    private inline fun <T> mapped(block: () -> T): T {
        try {
            return block()
        } catch (exc: ApiException) {
            throw exc.toRemoteDesktopException()
        }
    }

    private companion object {
        const val INVALID_RESPONSE = "The server returned an invalid response."
    }
}

/** Maps the shared error envelope onto [RemoteDesktopException]; unknown codes keep their text. */
internal fun ApiException.toRemoteDesktopException(): RemoteDesktopException {
    val rawCode = errorCode?.takeIf(String::isNotBlank)
    val details = errorDetails
    val rawReason = details?.optNullableString("reason")
    return RemoteDesktopException(
        message = message,
        code = RemoteDesktopErrorCode.fromWireValue(rawCode),
        rawCode = rawCode,
        statusCode = statusCode,
        unavailableReason = rawReason?.let(RemoteDesktopUnavailableReason::fromWireValue),
        details = details.toMap(),
        cause = this,
    )
}
