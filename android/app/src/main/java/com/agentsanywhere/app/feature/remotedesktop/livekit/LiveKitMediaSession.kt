package com.agentsanywhere.app.feature.remotedesktop.livekit

import android.content.Context
import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.feature.remotedesktop.MediaSession
import com.agentsanywhere.app.feature.remotedesktop.MediaSessionEvent
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.TrackEvent
import io.livekit.android.renderer.TextureViewRenderer
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import android.graphics.Bitmap
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
 *   `TextureViewRenderer` and `TextureViewRenderer`, both are `VideoSink`s, and
 *   `VideoTrack.addRenderer(VideoSink)` is how a view is bound. This class therefore
 *   exposes `attach`/`detach` on [RemoteDesktopVideoSurface] instead of a `VideoRenderer`
 *   parameter.
 *
 * The viewer is video-only: `audio = false` keeps the SDK from touching the audio device
 * (the merged manifest removes `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`), and `video =
 * false` makes sure no local track is ever published.
 */
private const val GL_FALLBACK_DELAY_MS = 3_000L

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
    private var sinkFrames = 0L
    private val compatFrames = MutableStateFlow<Bitmap?>(null)
    private var lastCompatAt = 0L
    @Volatile private var glFirstFrame = false
    private val compatEnabled = java.util.concurrent.atomic.AtomicBoolean(false)
    override val compatFrame: StateFlow<Bitmap?> = compatFrames
    private var renderer: TextureViewRenderer? = null
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

    override fun initRenderer(renderer: TextureViewRenderer) {
        val egl = eglBase ?: runCatching { eglFactory() }
            .onFailure { error -> android.util.Log.w("RDVideo", "EglBase.create failed", error) }
            .getOrNull()
            ?.also { eglBase = it }
        if (egl == null) {
            android.util.Log.w("RDVideo", "no EGL context; the renderer stays black")
            return
        }
        runCatching {
            renderer.init(
                egl.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {
                        glFirstFrame = true
                        android.util.Log.i("RDVideo", "GL sink drew its first frame")
                    }

                    override fun onFrameResolutionChanged(
                        videoWidth: Int,
                        videoHeight: Int,
                        rotation: Int,
                    ) = Unit
                },
            )
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }.onSuccess {
            android.util.Log.i("RDVideo", "renderer initialised id=${System.identityHashCode(renderer)}")
            if (isEmulator()) {
                // Emulators advertise a GL sink that reports frames yet paints nothing, so
                // the bitmap path is the default there.
                compatEnabled.set(true)
                android.util.Log.i("RDVideo", "emulator: using the bitmap fallback")
            }
            scope.launch {
                kotlinx.coroutines.delay(GL_FALLBACK_DELAY_MS)
                if (!glFirstFrame && sinkFrames > 0) {
                    compatEnabled.set(true)
                    android.util.Log.w(
                        "RDVideo",
                        "GL sink drew nothing after ${GL_FALLBACK_DELAY_MS}ms; using the bitmap fallback",
                    )
                }
            }
        }.onFailure { error ->
            android.util.Log.w("RDVideo", "renderer.init failed", error)
        }
    }

    /**
     * Diagnostic tap: counts the frames the SDK actually hands to a sink, so a black
     * picture can be attributed to the renderer instead of the subscription.
     */
    private fun loggingSink(): livekit.org.webrtc.VideoSink {
        return livekit.org.webrtc.VideoSink { frame ->
            sinkFrames += 1
            if (sinkFrames == 1L || sinkFrames % 60L == 0L) {
                // Mean luma of the decoded frame: this separates "the decoder gets black
                // pixels" from "the renderer does not draw".
                val luma = runCatching {
                    val i420 = frame.buffer.toI420()!!
                    val y = i420.dataY
                    var sum = 0L
                    var count = 0
                    var index = 0
                    for (row in 0 until i420.height) {
                        for (col in 0 until i420.width) {
                            sum += (y[index + col].toInt() and 0xFF)
                            count += 1
                        }
                        index += i420.strideY
                    }
                    i420.release()
                    if (count == 0) -1.0 else sum.toDouble() / count
                }.getOrNull()
                android.util.Log.i(
                    "RDVideo",
                    "sink frame #$sinkFrames ${frame.rotatedWidth}x${frame.rotatedHeight} " +
                        "meanLuma=$luma",
                )
            }
        }
    }

    override fun attach(renderer: TextureViewRenderer) {
        // `AndroidView.update` calls this on every recomposition: a renderer that is already
        // bound must not be added to the track twice.
        if (this.renderer === renderer) return
        if (this.renderer != null) detach(this.renderer!!)
        this.renderer = renderer
        android.util.Log.i(
            "RDVideo",
            "attach id=${System.identityHashCode(renderer)} track=${videoTrack != null}",
        )
        videoTrack?.let { track ->
            // Forward frames to the view from one sink: this both feeds the renderer and
            // proves (via `RDVideo` logs) that the frame reached the drawing path.
            val tee = livekit.org.webrtc.VideoSink { frame ->
                sinkFrames += 1
                renderer.onFrame(frame)
                // Compatibility path: turn decoded frames into bitmaps so the viewer can draw
                // them without the WebRTC GL sink (which paints nothing on this emulator).
                val now = android.os.SystemClock.elapsedRealtime()
                if (compatEnabled.get() && now - lastCompatAt >= 250L) {
                    lastCompatAt = now
                    runCatching { frame.toBitmap(step = 2) }.getOrNull()?.let { bitmap ->
                        compatFrames.value = bitmap
                    }
                }
            }
            runCatching { track.addRenderer(tee) }
                .onFailure { android.util.Log.w("RDVideo", "addRenderer failed", it) }
                .onSuccess { android.util.Log.i("RDVideo", "tee bound to track") }
        }
    }

    override fun detach(renderer: TextureViewRenderer) {
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
        renderer?.let { view ->
            runCatching { track.addRenderer(loggingSink()) }
            runCatching { track.addRenderer(view) }
        }
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


    /** Cheap emulator check: real phones never match these fingerprints. */
    private fun isEmulator(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT
        val model = android.os.Build.MODEL
        val product = android.os.Build.PRODUCT
        val hardware = android.os.Build.HARDWARE
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            model.contains("google_sdk") ||
            model.contains("Emulator") ||
            product.contains("sdk") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    /** I420 -> ARGB by [step] pixels; good enough for a fallback preview. */
    private fun livekit.org.webrtc.VideoFrame.toBitmap(step: Int): Bitmap? {
        val i420 = buffer.toI420() ?: return null
        val width = i420.width / step
        val height = i420.height / step
        if (width <= 0 || height <= 0) {
            i420.release()
            return null
        }
        val pixels = IntArray(width * height)
        val y = i420.dataY
        val u = i420.dataU
        val v = i420.dataV
        for (row in 0 until height) {
            val sourceRow = row * step
            for (col in 0 until width) {
                val sourceCol = col * step
                val luma = (y[sourceRow * i420.strideY + sourceCol].toInt() and 0xFF) - 16
                val chromaIndex =
                    (sourceRow / 2) * i420.strideU + (sourceCol / 2)
                val cb = (u[chromaIndex].toInt() and 0xFF) - 128
                val cr = (v[chromaIndex].toInt() and 0xFF) - 128
                val c = (luma.coerceAtLeast(0) * 1.164).toInt()
                val r = (c + 1.596 * cr).toInt().coerceIn(0, 255)
                val g = (c - 0.392 * cb - 0.813 * cr).toInt().coerceIn(0, 255)
                val b = (c + 2.017 * cb).toInt().coerceIn(0, 255)
                pixels[row * width + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        i420.release()
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
