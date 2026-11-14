package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopCapability
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.api.RemoteDesktopHostInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDesktopAvailabilityTest {
    @Test
    fun entryFollowsTheViewCapability() {
        assertTrue(remoteDesktopEntryVisible(hostInfo(capability())))
        assertFalse(remoteDesktopEntryVisible(hostInfo(capability(available = false))))
        assertFalse(remoteDesktopEntryVisible(hostInfo(capability(allowed = false))))
        assertFalse(remoteDesktopEntryVisible(hostInfo(capability(supported = false))))
        assertFalse(remoteDesktopEntryVisible(hostInfo()))
        assertFalse(remoteDesktopEntryVisible(null))
    }

    @Test
    fun aRestrictedControlOrClipboardStillOffersTheEntry() {
        // A watch-only device, or one with the clipboard policy off, must remain
        // watchable — otherwise the entry vanishes on the default device settings.
        assertTrue(
            remoteDesktopEntryVisible(
                hostInfo(
                    capability(id = RemoteDesktopCapabilityId.View),
                    capability(id = RemoteDesktopCapabilityId.Control, allowed = false),
                ),
            ),
        )
        assertTrue(
            remoteDesktopEntryVisible(
                hostInfo(
                    capability(id = RemoteDesktopCapabilityId.View),
                    capability(id = RemoteDesktopCapabilityId.Control),
                    capability(id = RemoteDesktopCapabilityId.Clipboard, allowed = false),
                ),
            ),
        )
        // Nothing to watch: only then is the entry hidden.
        assertFalse(
            remoteDesktopEntryVisible(
                hostInfo(
                    capability(id = RemoteDesktopCapabilityId.View, available = false),
                    capability(id = RemoteDesktopCapabilityId.Control),
                ),
            ),
        )
    }

    @Test
    fun capabilityHelperFindsOneSwitch() {
        val info = hostInfo(
            capability(id = RemoteDesktopCapabilityId.View),
            capability(id = RemoteDesktopCapabilityId.Control, available = false),
        )

        assertTrue(info.capability(RemoteDesktopCapabilityId.View)?.isUsable() == true)
        assertFalse(info.capability(RemoteDesktopCapabilityId.Control)?.isUsable() == true)
        assertNull(info.capability(RemoteDesktopCapabilityId.Clipboard))
        assertNull((null as RemoteDesktopHostInfo?).capability(RemoteDesktopCapabilityId.View))
    }

    private fun hostInfo(vararg capabilities: RemoteDesktopCapability): RemoteDesktopHostInfo {
        return RemoteDesktopHostInfo(
            connectorId = "conn_x",
            capabilities = capabilities.toList(),
            host = null,
            serverTime = "2026-11-13T00:00:00Z",
        )
    }

    private fun capability(
        id: RemoteDesktopCapabilityId = RemoteDesktopCapabilityId.View,
        supported: Boolean = true,
        available: Boolean = true,
        allowed: Boolean = true,
    ): RemoteDesktopCapability {
        return RemoteDesktopCapability(
            capabilityId = id,
            capabilityIdRaw = id.wireValue,
            scope = "connector",
            connectorId = "conn_x",
            supported = supported,
            available = available,
            allowed = allowed,
            unavailableReason = null,
            unavailableReasonRaw = null,
            revision = 1L,
            metadata = emptyMap(),
        )
    }
}
