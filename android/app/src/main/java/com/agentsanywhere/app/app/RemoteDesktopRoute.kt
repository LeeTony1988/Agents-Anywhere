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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.collectAsState
import com.agentsanywhere.app.BuildConfig
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopApi
import com.agentsanywhere.app.ui.screens.remotedesktop.saveAndShareScreenshot
import com.agentsanywhere.app.api.RemoteDesktopScreenshotFormat
import com.agentsanywhere.app.api.RemoteDesktopScreenshotRequest
import com.agentsanywhere.app.api.RemoteDesktopHostInfo
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopAutoTakeover
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionController
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.feature.remotedesktop.capability
import com.agentsanywhere.app.feature.remotedesktop.livekit.LiveKitMediaSession
import com.agentsanywhere.app.feature.remotedesktop.remoteDesktopEntryVisible
import com.agentsanywhere.app.api.RemoteDesktopCapabilityId
import com.agentsanywhere.app.api.RemoteDesktopClientInfo
import com.agentsanywhere.app.api.RemoteDesktopViewport
import com.agentsanywhere.app.navigation.AppDestination
import com.agentsanywhere.app.ui.screens.remotedesktop.RemoteDesktopScreen
import com.agentsanywhere.app.ui.screens.remotedesktop.RemoteDesktopUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current.density
    val viewport = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        RemoteDesktopViewport(
            width = (configuration.screenWidthDp * density).roundToInt(),
            height = (configuration.screenHeightDp * density).roundToInt(),
            dpr = density.toDouble(),
        )
    }
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
    val controllerNotice by controller.notice.collectAsState()
    val stats by controller.stats.collectAsState()
    val scope = rememberCoroutineScope()
    var quality by remember { mutableStateOf(RemoteDesktopQualityPreset.Default) }
    var pointerMode by remember { mutableStateOf(RemotePointerMode.Direct) }
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
            quality = quality.qualityFor(viewport.width),
            client = RemoteDesktopClientInfo(
                platform = "android",
                appVersion = BuildConfig.VERSION_NAME,
                viewport = viewport,
            ),
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
    // otherwise the page sits in "watching only" and taps look broken. This must happen
    // **once per Server session**, not on every transition to `controlling = false`:
    // the create response already makes this client the controller, so the old
    // "takeControlRequested" flag was never set and the first manual release immediately
    // took control again.
    val autoTakeover = remember { RemoteDesktopAutoTakeover() }
    // Set just before a user-initiated release so the resulting `controlling = false`
    // is not reported as "control was revoked".
    var manualReleaseFor by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sessionState) {
        val live = sessionState as? RemoteDesktopSessionState.Live
        val controlling = live?.controlling ?: false
        val sessionId = live?.session?.id
        if (wasControlling && !controlling) {
            if (manualReleaseFor != sessionId) {
                notice = context.getString(R.string.remote_desktop_control_revoked)
            }
            manualReleaseFor = null
        }
        if (live != null && sessionId != null &&
            autoTakeover.shouldTakeControl(sessionId, controlling)
        ) {
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
            stats = stats,
            quality = quality,
            pointerMode = pointerMode,
            capabilitiesAvailable = !unavailable,
            unavailableReason = unavailableReason,
            notice = controllerNotice ?: notice,
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
            manualReleaseFor = (sessionState as? RemoteDesktopSessionState.Live)?.session?.id
            scope.launch { controller.releaseControl() }
        },
        onScreenshot = { captureScreenshot() },
        screenshotBusy = screenshotBusy,
        onQualityChange = { preset ->
            quality = preset
            // "Auto" sends the Server/host default knobs, so it also resets a previous
            // Clear/Smooth choice instead of leaving the old resolution in place.
            preset.qualityFor(viewport.width)?.let { controller.updateQuality(it) }
        },
        onPointerModeChange = { pointerMode = it },
        onNoticeShown = {
            notice = null
            controller.clearNotice()
        },
        onPauseInput = { controller.pauseInput() },
        onForeground = {
            controller.resumeInput()
            controller.requestKeyframe()
        },
        onBackgroundExpired = { leave() },
    )
}
