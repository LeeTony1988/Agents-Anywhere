package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopCapability
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.api.RemoteDesktopHostInfo

/** `supported` is the platform, `available` the live host, `allowed` the device policy. */
fun RemoteDesktopCapability.isUsable(): Boolean = supported && available && allowed

/**
 * The "watch this device's screen" entry follows the `view` capability only.
 *
 * Control and clipboard are per-action switches inside the viewer: a device that only
 * allows watching (or has the clipboard off) must still offer the entry, otherwise the
 * whole feature disappears on any host with a restricted policy. This matches the Web
 * client and `docs/api/remote-desktop.md`.
 */
fun remoteDesktopEntryVisible(hostInfo: RemoteDesktopHostInfo?): Boolean =
    hostInfo?.capability(RemoteDesktopCapabilityId.View)?.isUsable() == true

fun RemoteDesktopHostInfo?.capability(id: RemoteDesktopCapabilityId): RemoteDesktopCapability? =
    this?.capabilities?.firstOrNull { it.capabilityId == id }
