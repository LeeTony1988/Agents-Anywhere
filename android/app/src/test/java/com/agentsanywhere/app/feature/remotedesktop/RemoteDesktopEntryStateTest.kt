package com.agentsanywhere.app.feature.remotedesktop

import com.agentsanywhere.app.api.RemoteDesktopCapability
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.api.RemoteDesktopHostFacts
import com.agentsanywhere.app.api.RemoteDesktopHostInfo
import com.agentsanywhere.app.api.RemoteDesktopInputSupport
import com.agentsanywhere.app.api.RemoteDesktopPermissionState
import com.agentsanywhere.app.api.RemoteDesktopHostPermissions
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device page entry is capability driven: it appears when the `view` capability is
 * supported, available and allowed, and it carries the reason the Server gave when it is
 * not. Control and clipboard are switches inside the viewer.
 */
class RemoteDesktopEntryStateTest {

    @Test
    fun aFullyUsableHostShowsTheEntry() {
        val state = remoteDesktopEntryState(hostInfo(capabilities = listOf(capability())))

        assertTrue(state is RemoteDesktopEntryState.Available)
        assertTrue(remoteDesktopEntryVisible((state as RemoteDesktopEntryState.Available).hostInfo))
    }

    @Test
    fun aHostWithoutCapabilitiesHidesTheEntry() {
        val state = remoteDesktopEntryState(hostInfo(capabilities = emptyList()))

        assertTrue(state is RemoteDesktopEntryState.Unavailable)
    }

    @Test
    fun anUnavailableViewCapabilityHidesTheEntryAndExplainsWhy() {
        val state = remoteDesktopEntryState(
            hostInfo(
                capabilities = listOf(
                    capability(available = false, reason = "host_not_running"),
                    capability(id = RemoteDesktopCapabilityId.Control),
                ),
            ),
        )

        val unavailable = state as RemoteDesktopEntryState.Unavailable
        assertEquals(RemoteDesktopUnavailableReason.HostNotRunning, unavailable.reason)
    }

    @Test
    fun aDisabledControlOrClipboardKeepsTheEntryForWatching() {
        for (blocked in listOf(RemoteDesktopCapabilityId.Control, RemoteDesktopCapabilityId.Clipboard)) {
            val state = remoteDesktopEntryState(
                hostInfo(
                    capabilities = listOf(
                        capability(),
                        capability(id = blocked, allowed = false, reason = "device_policy_disabled"),
                    ),
                ),
            )
            assertTrue("$blocked must not hide the entry", state is RemoteDesktopEntryState.Available)
        }
    }

    @Test
    fun aNullHostInfoHidesTheEntryWithoutAReason() {
        val state = remoteDesktopEntryState(null)

        assertEquals(RemoteDesktopEntryState.Unavailable(null, null), state)
    }

    @Test
    fun theHostFactReasonWinsOverTheCapabilityReason() {
        val info = hostInfo(
            capabilities = listOf(capability(available = false, reason = "connector_outdated")),
            hostReason = "screen_permission_denied",
        )

        assertEquals(
            RemoteDesktopUnavailableReason.ScreenPermissionDenied,
            remoteDesktopUnavailableReason(info),
        )
    }

    @Test
    fun anUnknownReasonStillHidesTheEntry() {
        val info = hostInfo(capabilities = listOf(capability(available = false, reason = "some_future_reason")))

        val unavailable = remoteDesktopEntryState(info) as RemoteDesktopEntryState.Unavailable
        assertEquals(RemoteDesktopUnavailableReason.Unknown, unavailable.reason)
    }

    private fun capability(
        id: RemoteDesktopCapabilityId = RemoteDesktopCapabilityId.View,
        supported: Boolean = true,
        available: Boolean = true,
        allowed: Boolean = true,
        reason: String? = null,
    ) = RemoteDesktopCapability(
        capabilityId = id,
        capabilityIdRaw = id.wireValue,
        scope = "connector",
        connectorId = CONNECTOR_ID,
        supported = supported,
        available = available,
        allowed = allowed,
        unavailableReason = reason?.let(RemoteDesktopUnavailableReason::fromWireValue),
        unavailableReasonRaw = reason,
        revision = 1L,
        metadata = emptyMap(),
    )

    private fun hostInfo(
        capabilities: List<RemoteDesktopCapability>,
        hostReason: String? = null,
    ) = RemoteDesktopHostInfo(
        connectorId = CONNECTOR_ID,
        capabilities = capabilities,
        host = RemoteDesktopHostFacts(
            supported = true,
            available = hostReason == null,
            unavailableReason = hostReason?.let(RemoteDesktopUnavailableReason::fromWireValue),
            unavailableReasonRaw = hostReason,
            platform = "darwin",
            arch = "arm64",
            hostVersion = "1.2.3",
            displays = emptyList(),
            codecs = listOf("h264"),
            maxFps = 30,
            input = RemoteDesktopInputSupport(pointer = true, keyboard = true, touch = false, clipboard = false),
            permissions = RemoteDesktopHostPermissions(
                screenRecording = RemoteDesktopPermissionState.Granted,
                accessibility = RemoteDesktopPermissionState.Granted,
                inputMonitoring = RemoteDesktopPermissionState.NotDetermined,
            ),
            activeSessions = 0,
        ),
        serverTime = null,
    )

    private companion object {
        const val CONNECTOR_ID = "conn_1"
    }
}
