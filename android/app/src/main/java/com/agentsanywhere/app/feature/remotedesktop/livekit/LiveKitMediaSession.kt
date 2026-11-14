package com.agentsanywhere.app.feature.remotedesktop.livekit

import android.content.Context
import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.feature.remotedesktop.MediaSession
import com.agentsanywhere.app.feature.remotedesktop.MediaSessionEvent
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.TrackEvent
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.DataPublishReliability
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.RendererCommon

/** `aa.input`: reliable, ordered JSON events (`input-event.schema.json`). */
const val REMOTE_DESKTOP_INPUT_TOPIC: String = "aa.input"

/** `aa.pointer`: lossy, unordered 12-byte frames (`pointer-frame.md`). */
const val REMOTE_DESKTOP_POINTER_TOPIC: String = "aa.pointer"

/**
 * LiveKit 2.30.0 implementation of [MediaSession].
 *
 * What the SDK actually offers, verified against the AAR
 * (`io.livekit:livekit-android:2.30.0`) rather than from memory:
 *
 * - `Room.connect(url, token, options)` is `suspend` and throws, and `ConnectOptions`
 *   carries `autoSubscribe`, `iceServers`, `rtcConfig`, `audio`, `video`, `protocolVersion`
 *   and `clientProtocol`.
 * - Room level events are `room.events.events`, a `SharedFlow<RoomEvent>` (`EventListenable`
 *   exposes `events`), with `Connected`/`Reconnected`/`Reconnecting`/`Disconnected`/
 *   `FailedToConnect`/`TrackSubscribed`/`TrackSubscriptionFailed` branches.
 * - `RemoteTrackPublication.setSubscribed(Boolean)` plus
 *   `TrackPublication.dimensions: Track.Dimensions` give the encoded size;
 *   `Track.events.events` emits `TrackEvent.VideoDimensionsChanged` when it changes.
 * - Data publishing is `suspend LocalParticipant.publishData(data, reliability, topic,
 *   destinationIdentities)` returning `Result<Unit>`, with `DataPublishReliability.RELIABLE`
 *   and `LOSSY`.
 * - There is **no** `io.livekit.android.renderer.VideoRenderer` in 2.30.0: the renderers are
 *   `SurfaceViewRenderer` and `SurfaceViewRenderer`, both are `VideoSink`s, and
 *   `VideoTrack.addRenderer(VideoSink)` is how a view is bound. This class therefore
 *   exposes `attach`/`detach` on [RemoteDesktopVideoSurface] instead of a `VideoRenderer`
 *   parameter.
 *
 * The viewer is video-only: `audio = false` keeps the SDK from touching the audio device
 * (the merged manifest removes `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`), and `video =
 * false` makes sure no local track is ever published.
 */
class LiveKitMediaSession(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val roomFactory: (Context) -> Room = { LiveKit.create(it) },
    private val eglFactory: () -> EglBase = { EglBase.create() },
) : MediaSession, RemoteDesktopVideoSurface {

    private val appContext: Context = context.applicationContext

    // A SharedFlow with a buffer keeps a burst of connect/disconnect events from being lost
    // when the controller subscribes a beat after `connect()` returned.
    private val _events = MutableSharedFlow<MediaSessionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<MediaSessionEvent> = _events.asSharedFlow()

    private var room: Room? = null
    private var roomEventsJob: Job? = null
    private var trackEventsJob: Job? = null
    private var videoTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private var eglBase: EglBase? = null
    private var joinedIdentity: String? = null

    /** LiveKit identity this client joined the room with; null when not joined. */
    val identity: String?
        get() = joinedIdentity

    override suspend fun connect(grant: RemoteDesktopMediaGrant) {
        try {
            val existing = room
            if (existing != null && existing.state == Room.State.CONNECTED && joinedIdentity == grant.identity) {
                // A renewed token does not need a new join: the data channel is already up and
                // reconnecting on every renewal would blank the picture for a second or two.
                emit(MediaSessionEvent.Connected)
                return
            }
            existing?.let { current -> runCatching { current.disconnect() } }
            clearVideoTrack()
            val active = room ?: createRoom().also { room = it }
            active.connect(grant.url, grant.token, connectOptions(grant))
            joinedIdentity = grant.identity
            emit(MediaSessionEvent.Connected)
            subscribePublishedVideoTracks(active)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Never let a media failure crash the app: the controller turns this into a
            // Reconnecting overlay and the 90 s Server window keeps the session alive.
            joinedIdentity = null
            clearVideoTrack()
            emit(MediaSessionEvent.Disconnected)
        }
    }

    override fun disconnect() {
        detachRenderer()
        trackEventsJob?.cancel()
        trackEventsJob = null
        clearVideoTrack()
        joinedIdentity = null
        room?.let { current -> runCatching { current.disconnect() } }
    }

    /** Full teardown, including the WebRTC room and the EGL context. Call when leaving the screen. */
    fun release() {
        disconnect()
        roomEventsJob?.cancel()
        roomEventsJob = null
        room?.let { current -> runCatching { current.release() } }
        room = null
        eglBase?.let { current -> runCatching { current.release() } }
        eglBase = null
    }

    override fun sendInput(json: String) {
        publishData(
            payload = json.toByteArray(Charsets.UTF_8),
            reliability = DataPublishReliability.RELIABLE,
            topic = REMOTE_DESKTOP_INPUT_TOPIC,
        )
    }

    override fun sendPointerFrame(bytes: ByteArray) {
        publishData(
            payload = bytes,
            reliability = DataPublishReliability.LOSSY,
            topic = REMOTE_DESKTOP_POINTER_TOPIC,
        )
    }

    override fun initRenderer(renderer: SurfaceViewRenderer) {
        val egl = eglBase ?: runCatching { eglFactory() }.getOrNull()?.also { eglBase = it }
        egl?.let { context ->
            runCatching {
                renderer.init(context.eglBaseContext, null)
                renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            }
        }
    }

    override fun attach(renderer: SurfaceViewRenderer) {
        // `AndroidView.update` calls this on every recomposition: a renderer that is already
        // bound must not be added to the track twice.
        if (this.renderer === renderer) return
        if (this.renderer != null) detach(this.renderer!!)
        this.renderer = renderer
        videoTrack?.let { track -> runCatching { track.addRenderer(renderer) } }
    }

    override fun detach(renderer: SurfaceViewRenderer) {
        videoTrack?.let { track -> runCatching { track.removeRenderer(renderer) } }
        runCatching { renderer.release() }
        if (this.renderer === renderer) this.renderer = null
    }

    // ---- room plumbing -----------------------------------------------------

    private fun createRoom(): Room {
        val created = roomFactory(appContext)
        roomEventsJob = scope.launch {
            created.events.events.collect { event -> onRoomEvent(event) }
        }
        return created
    }

    private fun onRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.Connected, is RoomEvent.Reconnected -> emit(MediaSessionEvent.Connected)

            is RoomEvent.Reconnecting -> emit(MediaSessionEvent.Reconnecting(MEDIA_RECONNECTING_REASON))

            is RoomEvent.Disconnected -> {
                clearVideoTrack()
                joinedIdentity = null
                emit(MediaSessionEvent.Disconnected)
            }

            is RoomEvent.FailedToConnect -> {
                clearVideoTrack()
                joinedIdentity = null
                emit(MediaSessionEvent.Disconnected)
            }

            is RoomEvent.TrackSubscribed -> onTrackSubscribed(event)

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track === videoTrack) clearVideoTrack()
            }

            is RoomEvent.TrackSubscriptionFailed ->
                emit(MediaSessionEvent.Reconnecting(MEDIA_SUBSCRIPTION_FAILED_REASON))

            else -> Unit
        }
    }

    private fun onTrackSubscribed(event: RoomEvent.TrackSubscribed) {
        val publication = event.publication as? RemoteTrackPublication ?: return
        // Subscribing explicitly keeps the behaviour independent of `autoSubscribe` and of a
        // Server that revokes subscription permission for viewers.
        runCatching { publication.setSubscribed(true) }
        val track = event.track as? VideoTrack ?: return
        bindVideoTrack(track)
        emitTrackSize(publication.dimensions)
    }

    private fun bindVideoTrack(track: VideoTrack) {
        if (videoTrack === track) return
        clearVideoTrack()
        videoTrack = track
        renderer?.let { view -> runCatching { track.addRenderer(view) } }
        trackEventsJob?.cancel()
        trackEventsJob = scope.launch {
            track.events.events.collect { event ->
                if (event is TrackEvent.VideoDimensionsChanged) emitTrackSize(event.newDimensions)
            }
        }
    }

    /**
     * A track that was already published when the room finished joining cannot produce a
     * `TrackSubscribed` event for us, so the existing publications are subscribed (and bound)
     * once after every join.
     */
    private fun subscribePublishedVideoTracks(room: Room) {
        room.remoteParticipants.values.forEach { participant ->
            participant.videoTrackPublications.forEach { (publication, track) ->
                val remote = publication as? RemoteTrackPublication ?: return@forEach
                runCatching { remote.setSubscribed(true) }
                val video = track as? VideoTrack ?: return@forEach
                bindVideoTrack(video)
                emitTrackSize(remote.dimensions)
            }
        }
    }

    private fun clearVideoTrack() {
        trackEventsJob?.cancel()
        trackEventsJob = null
        videoTrack?.let { track -> renderer?.let { view -> runCatching { track.removeRenderer(view) } } }
        videoTrack = null
    }

    private fun detachRenderer() {
        val view = renderer ?: return
        videoTrack?.let { track -> runCatching { track.removeRenderer(view) } }
        renderer = null
    }

    private fun emitTrackSize(dimensions: Track.Dimensions?) {
        val width = dimensions?.width ?: 0
        val height = dimensions?.height ?: 0
        if (width <= 0 || height <= 0) return
        emit(MediaSessionEvent.TrackAvailable(width, height))
    }

    private fun connectOptions(grant: RemoteDesktopMediaGrant): ConnectOptions {
        val iceServers = grant.iceServers.mapNotNull { server ->
            val urls = server.urls.filter { it.isNotBlank() }
            if (urls.isEmpty()) return@mapNotNull null
            runCatching {
                val builder = PeerConnection.IceServer.builder(urls)
                server.username?.takeIf { it.isNotBlank() }?.let { builder.setUsername(it) }
                server.credential?.takeIf { it.isNotBlank() }?.let { builder.setPassword(it) }
                builder.createIceServer()
            }.getOrNull()
        }
        return ConnectOptions(
            autoSubscribe = true,
            iceServers = iceServers,
            audio = false,
            video = false,
        )
    }

    private fun publishData(payload: ByteArray, reliability: DataPublishReliability, topic: String) {
        val participant = room?.localParticipant ?: return
        scope.launch {
            try {
                participant.publishData(payload, reliability, topic)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // A refused data packet is not a connection failure: the next pointer frame
                // carries the newest position, so the stream self-heals.
            }
        }
    }

    private fun emit(event: MediaSessionEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val MEDIA_RECONNECTING_REASON = "media_reconnecting"
        const val MEDIA_SUBSCRIPTION_FAILED_REASON = "track_subscription_failed"
    }
}
