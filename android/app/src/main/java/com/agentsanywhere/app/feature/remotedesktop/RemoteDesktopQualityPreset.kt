package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopQuality

/**
 * The three quality steps of the viewer's menu.
 *
 * Text needs resolution before it needs frames: 1280 px is only for a weak link, 2560 px
 * keeps a 1080p/1440p desktop readable on a phone in landscape, and `Auto` uses a
 * lighter 1920 px fallback. Bitrate is deliberately not a preset knob: the host scales
 * its encoder ceiling with the actual capture size and frame rate, and WebRTC bandwidth
 * estimation decides how many of those bits the link can carry. The viewer starts on
 * [Clear] because the previous default (1600 px at 4000 kbps) was visibly soft on a
 * phone, especially after rotating to full screen.
 */
enum class RemoteDesktopQualityPreset(
    /** Stable id used as the persisted/remembered selection. */
    val id: String,
    /** `remoteDesktop.quality` to forward on the control socket. */
    val quality: RemoteDesktopQuality?,
) {
    Smooth(
        id = "smooth",
        quality = RemoteDesktopQuality(maxFps = 30, maxWidth = 1_280),
    ),
    Clear(
        id = "clear",
        quality = RemoteDesktopQuality(maxFps = 30, maxWidth = 2_560),
    ),
    Auto(
        id = "auto",
        // A lighter desktop-wide fallback; the host computes the bitrate budget for
        // whatever resolution the actual display has.
        quality = RemoteDesktopQuality(maxFps = 30, maxWidth = 1_920),
    ),
    ;

    companion object {
        val Default: RemoteDesktopQualityPreset = Clear

        fun fromId(id: String?): RemoteDesktopQualityPreset =
            entries.firstOrNull { it.id == id } ?: Default
    }

    /**
     * Keeps the requested resolution useful for the actual phone viewport.
     *
     * A 2560-wide stream is right for a 2800 px landscape phone, but pointless on a
     * 1260 px portrait phone: capture at least 1920 so zooming into text still has
     * headroom, and never capture below the stream's own smallest preset.
     */
    fun qualityFor(viewportWidthPx: Int?): RemoteDesktopQuality? {
        val base = quality ?: return null
        val requested = base.maxWidth ?: return base
        if (viewportWidthPx == null || viewportWidthPx <= 0) return base
        val lowerBound = minOf(1920, requested)
        val width = minOf(requested, maxOf(lowerBound, viewportWidthPx))
        return if (width == requested) base else base.copy(maxWidth = width)
    }
}
