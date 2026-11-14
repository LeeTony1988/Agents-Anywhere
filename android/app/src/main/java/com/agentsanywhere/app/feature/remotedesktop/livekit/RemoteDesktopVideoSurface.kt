package com.agentsanywhere.app.feature.remotedesktop.livekit

import io.livekit.android.renderer.SurfaceViewRenderer

/**
 * The seam between the viewer UI and the media layer's video sink.
 *
 * `feature/remotedesktop` must not depend on Compose, so the screen owns the
 * `AndroidView` that wraps a [SurfaceViewRenderer] and only asks the media session to bind
 * or unbind that view. The LiveKit 2.30 renderer type is
 * `io.livekit.android.renderer.SurfaceViewRenderer` (`SurfaceViewRenderer` also exists, but
 * a `TextureView` can be transformed and clipped by the local pan/zoom without a
 * `SurfaceView` punch-through).
 */
interface RemoteDesktopVideoSurface {
    /**
     * Prepares [renderer] (EGL context, scaling) before it is attached to the window.
     * LiveKit's renderer drops frames until `init` has run, so the screen calls this from
     * the `AndroidView` factory instead of from `update`.
     */
    fun initRenderer(renderer: SurfaceViewRenderer)

    /** Binds [renderer] to the subscribed remote video track. Idempotent. */
    fun attach(renderer: SurfaceViewRenderer)

    /** Unbinds and releases [renderer]; safe to call for a renderer that was never attached. */
    fun detach(renderer: SurfaceViewRenderer)
}
