package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopApi
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopException
import com.agentsanywhere.app.api.RemoteDesktopHostInfo
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import kotlinx.coroutines.CancellationException

/**
 * Whether the device page may offer "watch this device's screen".
 *
 * The decision itself is [remoteDesktopEntryVisible]: the entry stays hidden unless every
 * advertised capability is `supported && available && allowed`. The domain layer is here so
 * several call sites (device list, device detail, remote desktop screen) agree on it.
 */
sealed interface RemoteDesktopEntryState {
    /** `GET connectors/{id}/remote-desktop` in flight. */
    data object Loading : RemoteDesktopEntryState

    /** The Server and the host both say the feature is usable; the entry is shown. */
    data class Available(val hostInfo: RemoteDesktopHostInfo) : RemoteDesktopEntryState

    /** The Server answered, but a capability is missing: the entry stays hidden. */
    data class Unavailable(
        val hostInfo: RemoteDesktopHostInfo?,
        val reason: RemoteDesktopUnavailableReason?,
    ) : RemoteDesktopEntryState

    /** The probe itself failed; the page shows nothing and retries on the next visit. */
    data class Failed(val message: String, val code: RemoteDesktopErrorCode) : RemoteDesktopEntryState
}

/** Maps a probe result onto the entry state. */
fun remoteDesktopEntryState(hostInfo: RemoteDesktopHostInfo?): RemoteDesktopEntryState {
    if (hostInfo == null) return RemoteDesktopEntryState.Unavailable(null, null)
    if (!remoteDesktopEntryVisible(hostInfo)) {
        return RemoteDesktopEntryState.Unavailable(hostInfo, remoteDesktopUnavailableReason(hostInfo))
    }
    return RemoteDesktopEntryState.Available(hostInfo)
}

/**
 * Reason to show when the entry cannot be used.
 *
 * The host's own fact wins (it describes the machine), then the first capability that is
 * not usable: with two capabilities, the one that is off is the interesting one.
 */
fun remoteDesktopUnavailableReason(hostInfo: RemoteDesktopHostInfo?): RemoteDesktopUnavailableReason? {
    val host = hostInfo?.host
    host?.unavailableReason?.let { return it }
    host?.unavailableReasonRaw?.let { return RemoteDesktopUnavailableReason.fromWireValue(it) }
    val blocked = hostInfo?.capabilities?.firstOrNull { !it.isUsable() } ?: return null
    blocked.unavailableReason?.let { return it }
    return blocked.unavailableReasonRaw?.let(RemoteDesktopUnavailableReason::fromWireValue)
}

/**
 * Loads the capability probe for one Connector. Nothing here touches media, so opening a
 * device page never creates a session or asks the host for consent.
 */
suspend fun loadRemoteDesktopEntry(
    api: RemoteDesktopApi,
    serverUrl: String,
    authorizationToken: String,
    connectorId: String,
): RemoteDesktopEntryState {
    if (connectorId.isBlank() || serverUrl.isBlank() || authorizationToken.isBlank()) {
        return RemoteDesktopEntryState.Unavailable(null, null)
    }
    return try {
        remoteDesktopEntryState(api.getHostInfo(serverUrl, authorizationToken, connectorId))
    } catch (error: CancellationException) {
        throw error
    } catch (error: RemoteDesktopException) {
        RemoteDesktopEntryState.Failed(error.message, error.code)
    } catch (error: Exception) {
        RemoteDesktopEntryState.Failed(
            message = error.message ?: "Could not check remote desktop.",
            code = RemoteDesktopErrorCode.Unknown,
        )
    }
}
