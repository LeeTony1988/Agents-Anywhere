package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopCapability
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.api.RemoteDesktopHostInfo

/** `supported` is the platform, `available` the live host, `allowed` the device policy. */
fun RemoteDesktopCapability.isUsable(): Boolean = supported && available && allowed

/**
 * The remote desktop entry point stays hidden unless every advertised capability is
 * usable (docs/api/remote-desktop.md: any false hides the button).
 */
fun remoteDesktopEntryVisible(hostInfo: RemoteDesktopHostInfo?): Boolean {
    val capabilities = hostInfo?.capabilities.orEmpty()
    return capabilities.isNotEmpty() && capabilities.all { it.isUsable() }
}

fun RemoteDesktopHostInfo?.capability(id: RemoteDesktopCapabilityId): RemoteDesktopCapability? =
    this?.capabilities?.firstOrNull { it.capabilityId == id }
