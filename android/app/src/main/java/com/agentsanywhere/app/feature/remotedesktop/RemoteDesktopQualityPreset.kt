package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopQuality

/**
 * The three quality steps of the viewer's menu.
 *
 * Values follow `docs/remote-desktop.md` section 7.7: the phone default is 30 fps, 1600 px
 * wide and 3000 kbps, text needs resolution, and fast scrolling needs frames. [Auto] sends
 * no knobs at all, which leaves the Server and the host on their own defaults.
 */
enum class RemoteDesktopQualityPreset(
    /** Stable id used as the persisted/remembered selection. */
    val id: String,
    /** `remoteDesktop.quality` to forward on the control socket; null means "keep defaults". */
    val quality: RemoteDesktopQuality?,
) {
    Smooth(
        id = "smooth",
        quality = RemoteDesktopQuality(maxFps = 30, maxBitrateKbps = 1_500, maxWidth = 1_280),
    ),
    Clear(
        id = "clear",
        quality = RemoteDesktopQuality(maxFps = 30, maxBitrateKbps = 6_000, maxWidth = 2_560),
    ),
    Auto(
        id = "auto",
        quality = null,
    ),
    ;

    companion object {
        val Default: RemoteDesktopQualityPreset = Auto

        fun fromId(id: String?): RemoteDesktopQualityPreset =
            entries.firstOrNull { it.id == id } ?: Default
    }
}
