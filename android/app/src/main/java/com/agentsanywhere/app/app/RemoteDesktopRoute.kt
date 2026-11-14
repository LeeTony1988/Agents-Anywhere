package com.agentsanywhere.app.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopApi
import com.agentsanywhere.app.ui.screens.remotedesktop.saveAndShareScreenshot
import com.agentsanywhere.app.api.RemoteDesktopScreenshotFormat
import com.agentsanywhere.app.api.RemoteDesktopScreenshotRequest
import com.agentsanywhere.app.api.RemoteDesktopHostInfo
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionController
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.feature.remotedesktop.capability
import com.agentsanywhere.app.feature.remotedesktop.livekit.LiveKitMediaSession
import com.agentsanywhere.app.feature.remotedesktop.remoteDesktopEntryVisible
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.navigation.AppDestination
import com.agentsanywhere.app.ui.screens.remotedesktop.RemoteDesktopScreen
import com.agentsanywhere.app.ui.screens.remotedesktop.RemoteDesktopUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * App-layer wiring of the remote desktop viewer.
 *
 * Everything expensive lives here and nothing is hard-coded: the Server URL and the bearer
 * token come from the signed-in session, the HTTP leg reuses the app-wide [RemoteDesktopApi]
 * (and therefore the same [com.agentsanywhere.app.api.ApiClient] token refresh), and the
 * LiveKit room is created from the Activity context only when this route is entered.
 */
@Composable
internal fun RemoteDesktopRoute(
    connectorId: String?,
    deviceName: String,
    serverUrl: String,
    authorizationToken: String,
    remoteDesktopApi: RemoteDesktopApi,
    backDestination: AppDestination,
    navigate: (AppDestination) -> Unit,
) {
    val context = LocalContext.current
    if (connectorId.isNullOrBlank()) {
        // Reached without a device (a stale back stack entry): nothing to watch.
        LaunchedEffect(Unit) { navigate(backDestination) }
        return
    }
    val media = remember(connectorId) { LiveKitMediaSession(context.applicationContext) }
    val controller = remember(connectorId, serverUrl, authorizationToken) {
        RemoteDesktopSessionController(
            media = media,
            serverUrl = serverUrl,
            authorizationToken = authorizationToken,
            api = remoteDesktopApi,
        )
    }
    val sessionState by controller.state.collectAsState()
    val trackSize by controller.trackSize.collectAsState()
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(RemoteDesktopQualityPreset.Default) }
    var notice by remember { mutableStateOf<String?>(null) }
    var hostInfo by remember(connectorId) { mutableStateOf<RemoteDesktopHostInfo?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    var screenshotBusy by remember { mutableStateOf(false) }

    // The capability probe runs before (and independently of) the session so the screen can
    // explain why a host cannot be watched instead of failing on the media join.
    LaunchedEffect(connectorId, serverUrl, authorizationToken, attempt) {
        val id = connectorId ?: return@LaunchedEffect
        if (serverUrl.isBlank() || authorizationToken.isBlank()) return@LaunchedEffect
        hostInfo = runCatching {
            remoteDesktopApi.getHostInfo(serverUrl, authorizationToken, id)
        }.getOrNull()
    }

    LaunchedEffect(connectorId, serverUrl, authorizationToken, attempt) {
        val id = connectorId ?: return@LaunchedEffect
        if (serverUrl.isBlank() || authorizationToken.isBlank()) return@LaunchedEffect
        controller.start(
            connectorId = id,
            mode = RemoteDesktopMode.Control,
            quality = quality.quality,
        )
    }

    DisposableEffect(controller) {
        onDispose {
            controller.close()
            media.release()
        }
    }

    // A control takeover by someone else arrives as a state change, not as a message.
    var wasControlling by remember { mutableStateOf(false) }
    // The viewer opens in control mode, so take the lease as soon as the session is live:
    // otherwise the page sits in "watching only" and taps look broken.
    var takeControlRequested by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState) {
        val live = sessionState as? RemoteDesktopSessionState.Live
        val controlling = live?.controlling ?: false
        if (wasControlling && !controlling) {
            notice = context.getString(R.string.remote_desktop_control_revoked)
        }
        if (live != null && !controlling && !takeControlRequested) {
            takeControlRequested = true
            runCatching { controller.takeControl() }
        }
        wasControlling = controlling
    }

    fun captureScreenshot() {
        if (screenshotBusy) return
        val activeSession = (sessionState as? RemoteDesktopSessionState.Live)?.session
            ?: (sessionState as? RemoteDesktopSessionState.Reconnecting)?.session
            ?: return
        val id = connectorId ?: return
        screenshotBusy = true
        scope.launch {
            runCatching {
                val shot = withContext(Dispatchers.IO) {
                    remoteDesktopApi.screenshot(
                        serverUrl,
                        authorizationToken,
                        activeSession.id,
                        RemoteDesktopScreenshotRequest(
                            format = RemoteDesktopScreenshotFormat.Jpeg,
                            maxWidth = 1920,
                            quality = 85,
                        ),
                    )
                }
                saveAndShareScreenshot(context, id, shot)
            }.onFailure { error ->
                notice = error.message ?: context.getString(R.string.remote_desktop_screenshot_failed)
            }
            screenshotBusy = false
        }
    }

    fun leave() {
        if (leaving) return
        leaving = true
        scope.launch {
            controller.end()
            navigate(backDestination)
        }
    }

    BackHandler { leave() }

    val unavailable = hostInfo != null && !remoteDesktopEntryVisible(hostInfo)
    val unavailableReason: RemoteDesktopUnavailableReason? = hostInfo?.host?.unavailableReason
        ?: hostInfo?.capability(RemoteDesktopCapabilityId.View)?.unavailableReason

    RemoteDesktopScreen(
        state = RemoteDesktopUiState(
            deviceName = deviceName,
            session = sessionState,
            controlling = (sessionState as? RemoteDesktopSessionState.Live)?.controlling ?: false,
            leaseExpiresAt = (sessionState as? RemoteDesktopSessionState.Live)
                ?.session?.controllerLeaseExpiresAt,
            quality = quality,
            capabilitiesAvailable = !unavailable,
            unavailableReason = unavailableReason,
            notice = notice,
        ),
        trackSize = trackSize,
        media = media,
        videoSurface = media,
        onBack = { leave() },
        onRetry = {
            attempt += 1
            notice = null
        },
        onEnd = { leave() },
        onTakeControl = {
            scope.launch { controller.takeControl() }
        },
        onReleaseControl = {
            scope.launch { controller.releaseControl() }
        },
        onScreenshot = { captureScreenshot() },
        screenshotBusy = screenshotBusy,
        onQualityChange = { preset ->
            quality = preset
            // "Auto" sends no knobs at all: the Server keeps the host's own defaults.
            preset.quality?.let { controller.updateQuality(it) }
        },
        onNoticeShown = { notice = null },
        onPauseInput = { controller.pauseInput() },
        onForeground = {
            controller.resumeInput()
            controller.requestKeyframe()
        },
        onBackgroundExpired = { leave() },
    )
}
