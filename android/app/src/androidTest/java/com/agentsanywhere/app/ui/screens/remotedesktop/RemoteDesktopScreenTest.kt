package com.agentsanywhere.app.ui.screens.remotedesktop

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopMediaGrant
import com.agentsanywhere.app.api.RemoteDesktopMediaReference
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopQuality
import com.agentsanywhere.app.api.RemoteDesktopSession
import com.agentsanywhere.app.api.RemoteDesktopState
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.MediaSession
import com.agentsanywhere.app.feature.remotedesktop.MediaSessionEvent
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Static UI of the two blocking states, rendered from a fake controller state and a fake
 * media session: no room, no network and no LiveKit object is created here.
 */
@RunWith(AndroidJUnit4::class)
class RemoteDesktopScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val media = FakeMediaSession()
    private var ended = 0
    private var retried = 0
    private var screenshots = 0
    private var quality = RemoteDesktopQualityPreset.Default

    @Test
    fun awaitingConsentExplainsTheDeviceAndOffersCancel() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.AwaitingConsent(session()),
            ),
        )

        compose.onNodeWithText(text(R.string.remote_desktop_status_awaiting)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_desktop_awaiting_title)).assertIsDisplayed()
        compose.onNodeWithText(deviceNameText()).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_desktop_cancel)).performClick()
        compose.runOnIdle { assertEquals(1, ended) }
    }

    @Test
    fun aFailedJoinShowsTheMappedErrorAndRetries() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Failed(
                    message = "media node unavailable",
                    code = RemoteDesktopErrorCode.MediaUnavailable,
                    retryable = true,
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.remote_desktop_error_media)).assertIsDisplayed()
        compose.onNodeWithText("media node unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_desktop_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun aFinishedSessionShowsTheEndReason() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Ended(
                    session = session().copy(state = RemoteDesktopState.Ended, stateRaw = "ended"),
                    reason = RemoteDesktopEndReason.HostEnded,
                    reasonRaw = "host_ended",
                ),
            ),
        )

        compose.onNodeWithText(text(R.string.remote_desktop_ended_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_desktop_ended_host)).assertIsDisplayed()
    }

    @Test
    fun anUnavailableCapabilityExplainsTheReasonInsteadOfConnecting() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Idle,
                capabilitiesAvailable = false,
                unavailableReason = RemoteDesktopUnavailableReason.ConnectorOffline,
            ),
        )

        compose.onNodeWithText(text(R.string.remote_desktop_unavailable_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.remote_desktop_reason_connector_offline)).assertIsDisplayed()
    }

    private fun show(state: RemoteDesktopUiState) {
        compose.setContent {
            AgentsAnywhereTheme {
                RemoteDesktopScreen(
                    state = state,
                    trackSize = null,
                    media = media,
                    videoSurface = null,
                    onBack = {},
                    onRetry = { retried += 1 },
                    onEnd = { ended += 1 },
                    onTakeControl = {},
                    onReleaseControl = {},
                    onScreenshot = { screenshots += 1 },
                    onQualityChange = { quality = it },
                    onNoticeShown = {},
                )
            }
        }
    }

    private fun text(id: Int): String = compose.activity.getString(id)

    private fun deviceNameText(): String =
        compose.activity.getString(R.string.remote_desktop_awaiting_body, DEVICE_NAME)

    private fun session() = RemoteDesktopSession(
        id = "rds_1",
        connectorId = "conn_1",
        mode = RemoteDesktopMode.Control,
        modeRaw = "control",
        displayId = null,
        state = RemoteDesktopState.AwaitingConsent,
        stateRaw = "awaiting_consent",
        endReason = null,
        endReasonRaw = null,
        controller = null,
        controllerLeaseExpiresAt = null,
        linkedSessionId = null,
        media = RemoteDesktopMediaReference(provider = "livekit", room = "rd_rds_1", url = "wss://rtc.example"),
        quality = RemoteDesktopQuality(maxFps = 30),
        stats = null,
        error = null,
        viewerCount = 1,
        createdAt = "2026-01-01T00:00:00Z",
        startedAt = null,
        endedAt = null,
    )

    private class FakeMediaSession : MediaSession {
        private val eventFlow = MutableSharedFlow<MediaSessionEvent>(extraBufferCapacity = 8)
        override val events = eventFlow
        val inputs = MutableStateFlow(0)

        override suspend fun connect(grant: RemoteDesktopMediaGrant) = Unit

        override fun disconnect() = Unit

        override fun sendInput(json: String) {
            inputs.value += 1
        }

        override fun sendPointerFrame(bytes: ByteArray) = Unit
    }

    private companion object {
        const val DEVICE_NAME = "Studio Mac"
    }
}
