package com.agentsanywhere.app.ui.screens.remotedesktop

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
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
import com.agentsanywhere.app.feature.remotedesktop.input.REMOTE_POINTER_BUTTON_LEFT
import com.agentsanywhere.app.feature.remotedesktop.input.decodePointerFrame
import com.agentsanywhere.app.ui.designsystem.AgentsAnywhereTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun liveSessionOffersScreenshotEvenWhenWatching() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = false),
            ),
        )

        // Watching is enough to capture a frame; the button sits in the side drawer.
        compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_controls_show))
            .performClick()
        compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_screenshot))
            .assertIsDisplayed()
    }

    @Test
    fun mouseTrackpadConsumesTheGestureAndMovesTheRemotePointer() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.waitForIdle()
        val framesBefore = media.frames.value
        val inputsBefore = media.inputs.value
        compose.onNodeWithTag("remote-desktop-trackpad").assertIsDisplayed().performTouchInput {
            // Start away from the cursor ring: a swipe starting on the ring is the new
            // hold-and-drag gesture and legitimately sends a button down/up pair.
            swipe(
                start = center + Offset(-width * 0.30f, 0f),
                end = center + Offset(width * 0.15f, 0f),
                durationMillis = 200L,
            )
        }

        compose.runOnIdle {
            assertTrue(
                "A swipe inside the trackpad must emit aa.pointer frames",
                media.frames.value > framesBefore,
            )
            assertEquals(
                "The trackpad must not fall through to the picture's direct-drag path",
                inputsBefore,
                media.inputs.value,
            )
        }
    }

    @Test
    fun grabbingTheTrackpadCursorHoldsAndReleasesTheLeftButton() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.waitForIdle()
        val framesBefore = media.frames.value
        compose.onNodeWithTag("remote-desktop-trackpad").assertIsDisplayed().performTouchInput {
            down(center)
            advanceEventTime(16)
            moveBy(Offset(24f, 0f))
            advanceEventTime(16)
            up()
        }

        compose.runOnIdle {
            val pointers = media.inputJson
                .map { JSONObject(it) }
                .filter { it.getString("t") == "pointer" }
            val downIndex = pointers.indexOfFirst { it.getString("a") == "down" && it.getString("b") == "left" }
            val upIndex = pointers.indexOfFirst { it.getString("a") == "up" && it.getString("b") == "left" }
            assertTrue("Pressing the cursor ring must press the left button", downIndex >= 0)
            assertTrue("The left button must be released after the press", upIndex > downIndex)

            val dragFrames = media.pointerFrames
                .drop(framesBefore)
                .mapNotNull { decodePointerFrame(it) }
            assertTrue(
                "Frames during the grab must carry the held left button",
                dragFrames.any { it.buttons and REMOTE_POINTER_BUTTON_LEFT != 0 },
            )
            val centre = (0.5f * 0xFFFF).toInt()
            assertTrue(
                "Dragging while the ring is held must move the cursor",
                dragFrames.any { kotlin.math.abs(it.x - centre) > 1_000 || kotlin.math.abs(it.y - centre) > 1_000 },
            )
            assertEquals("The release frame must clear the button bits", 0, dragFrames.last().buttons)
        }
    }

    @Test
    fun doubleTappingTheTrackpadKeepsTheDragLockArmed() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("remote-desktop-trackpad").assertIsDisplayed().performTouchInput {
            val target = center + Offset(-width * 0.25f, 0f)
            down(target)
            up()
            advanceEventTime(120)
            down(target)
            up()
        }

        compose.onNodeWithText(text(R.string.remote_desktop_trackpad_locked)).assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(
                "Finishing the double tap must not release the drag lock it just armed",
                media.inputJson.map { JSONObject(it) }.none { it.getString("t") == "release_all" },
            )
        }
    }

    @Test
    fun tappingTheGapBetweenTrackpadAndRailDoesNotReachTheVideo() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.waitForIdle()
        val inputsBefore = media.inputs.value
        compose.onNodeWithTag("remote-desktop-trackpad-gap").assertIsDisplayed().performTouchInput {
            down(center)
            up()
        }
        compose.runOnIdle {
            assertEquals(
                "The gap is a dead zone, not a direct-touch click on the remote desktop",
                inputsBefore,
                media.inputs.value,
            )
        }
    }

    @Test
    fun draggingTheTrackpadScrollRailSendsWheelEvents() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithTag("remote-desktop-trackpad-rail").assertIsDisplayed().performTouchInput {
            down(center)
            advanceEventTime(50)
            moveBy(Offset(0f, 60f))
            advanceEventTime(50)
            up()
        }

        compose.runOnIdle {
            val wheels = media.inputJson
                .map { JSONObject(it) }
                .filter { it.getString("t") == "wheel" }
            assertTrue("Dragging the scroll rail must emit wheel events", wheels.isNotEmpty())
            assertTrue(
                "Dragging down must scroll the remote desktop down",
                wheels.sumOf { it.getDouble("dy") } > 0.0,
            )
        }
    }

    @Test
    fun theSideControlsDrawerOpensAndCloses() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
                controlling = true,
                pointerMode = com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode.Mouse,
            ),
        )
        compose.onNodeWithContentDescription(text(R.string.remote_desktop_screenshot)).assertDoesNotExist()
        // The closed state is a compact translucent icon: it must still be a 48 dp touch
        // target even though the visible circle is smaller.
        val handle = compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_controls_show))
            .assertIsDisplayed()
        val minTouchTarget = with(compose.density) { 48.dp.toPx() }
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The closed handle must be a 48 dp touch target: $handleBounds",
            handleBounds.height >= minTouchTarget && handleBounds.width >= minTouchTarget,
        )
        // The handle is icon-only: no visible label may sneak back into the closed state.
        compose
            .onNodeWithText(text(R.string.remote_desktop_controls_title))
            .assertDoesNotExist()
        handle.performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_desktop_screenshot)).assertIsDisplayed()
        val drawerRight = compose.onNodeWithTag("remote-desktop-controls-drawer")
            .fetchSemanticsNode().boundsInRoot.right
        val trackpadLeft = compose.onNodeWithTag("remote-desktop-trackpad")
            .fetchSemanticsNode().boundsInRoot.left
        assertTrue(
            "The open drawer must not cover the trackpad: drawerRight=$drawerRight trackpadLeft=$trackpadLeft",
            drawerRight <= trackpadLeft,
        )
        compose.onNodeWithContentDescription(text(R.string.remote_desktop_controls_hide)).performClick()
        compose.onNodeWithContentDescription(text(R.string.remote_desktop_screenshot)).assertDoesNotExist()
        val reopened = compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_controls_show))
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Closing the drawer must restore the same compact handle: $reopened",
            reopened.height >= minTouchTarget && reopened.width >= minTouchTarget,
        )
    }

    @Test
    fun screenshotButtonInvokesTheCallback() {
        show(
            RemoteDesktopUiState(
                deviceName = DEVICE_NAME,
                session = RemoteDesktopSessionState.Live(session(), controlling = true),
            ),
        )

        compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_controls_show))
            .performClick()
        compose
            .onNodeWithContentDescription(text(R.string.remote_desktop_screenshot))
            .performClick()
        compose.runOnIdle { assertEquals(1, screenshots) }
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
        val frames = MutableStateFlow(0)
        val inputJson = mutableListOf<String>()
        val pointerFrames = mutableListOf<ByteArray>()

        override suspend fun connect(grant: RemoteDesktopMediaGrant) = Unit

        override fun disconnect() = Unit

        override fun sendInput(json: String) {
            inputs.value += 1
            inputJson += json
        }

        override fun sendPointerFrame(bytes: ByteArray) {
            frames.value += 1
            pointerFrames += bytes
        }
    }

    private companion object {
        const val DEVICE_NAME = "Studio Mac"
    }
}
