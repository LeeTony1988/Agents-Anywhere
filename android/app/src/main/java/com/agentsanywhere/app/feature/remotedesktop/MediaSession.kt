package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputSink
import kotlinx.coroutines.flow.Flow

/**
 * The media leg of a remote desktop session: joins the LiveKit room, publishes input on the
 * `aa.input`/`aa.pointer` data channels and reports connection state.
 *
 * This is an interface on purpose. The controller must not build LiveKit objects, and unit
 * tests must not need a room; the LiveKit implementation (and the SurfaceView/TrackRenderer
 * wiring that goes with it) is the UI agent's deliverable.
 */
interface MediaSession : RemoteInputSink {
    /** Joins the room with a freshly issued grant. Called again on token renewal. */
    suspend fun connect(grant: RemoteDesktopMediaGrant)

    /** Leaves the room and releases every track. Safe to call when never connected. */
    fun disconnect()

    /** Connection lifecycle of the room; collect this to drive [RemoteDesktopSessionController]. */
    val events: Flow<MediaSessionEvent>
}

/** Room-level events the controller reacts to. */
sealed interface MediaSessionEvent {
    /** The room is joined and data publishing is ready. */
    data object Connected : MediaSessionEvent

    /** A remote video track is subscribed; the size is the encoded stream size (0 when unknown). */
    data class TrackAvailable(val width: Int, val height: Int) : MediaSessionEvent

    /** The room was left, either by us or by the Server. */
    data object Disconnected : MediaSessionEvent

    /** ICE restart in progress; the UI shows a reconnecting overlay after a few seconds. */
    data class Reconnecting(val reason: String? = null) : MediaSessionEvent
}

/** Encoded stream size of the subscribed video track, for aspect-fit rendering. */
data class RemoteDesktopTrackSize(val width: Int, val height: Int)
