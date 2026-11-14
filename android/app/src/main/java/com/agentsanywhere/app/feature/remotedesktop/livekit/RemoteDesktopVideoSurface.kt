package com.agentsanywhere.app.feature.remotedesktop.livekit

import android.graphics.Bitmap
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.flow.StateFlow

/**
 * The seam between the viewer UI and the media layer's video sink.
 *
 * `feature/remotedesktop` must not depend on Compose, so the screen owns the
 * `AndroidView` that wraps a [TextureViewRenderer] and only asks the media session to bind
 * or unbind that view. The LiveKit 2.30 renderer type is
 * `io.livekit.android.renderer.TextureViewRenderer` (`TextureViewRenderer` also exists, but
 * a `TextureView` can be transformed and clipped by the local pan/zoom without a
 * `SurfaceView` punch-through).
 */
interface RemoteDesktopVideoSurface {
    /**
     * Prepares [renderer] (EGL context, scaling) before it is attached to the window.
     * LiveKit's renderer drops frames until `init` has run, so the screen calls this from
     * the `AndroidView` factory instead of from `update`.
     */
    fun initRenderer(renderer: TextureViewRenderer)

    /** Binds [renderer] to the subscribed remote video track. Idempotent. */
    fun attach(renderer: TextureViewRenderer)

    /** Unbinds and releases [renderer]; safe to call for a renderer that was never attached. */
    fun detach(renderer: TextureViewRenderer)

    /**
     * Decoded frames as bitmaps, for devices whose WebRTC GL sink paints nothing (seen on
     * emulators). The viewer falls back to this when it is non-null.
     */
    val compatFrame: StateFlow<Bitmap?>?
        get() = null
}
