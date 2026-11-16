package com.agentsanywhere.app.ui.screens.remotedesktop

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minimize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopStats
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.MediaSession
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopLifecycleAction
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopLifecyclePolicy
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopTrackSize
import com.agentsanywhere.app.feature.remotedesktop.input.RELEASE_REASON_HANDOFF
import com.agentsanywhere.app.feature.remotedesktop.input.RELEASE_REASON_MANUAL
import com.agentsanywhere.app.feature.remotedesktop.input.RELEASE_REASON_PAGE_HIDE
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteGestureAction
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteGestureStateMachine
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputDispatcher
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputSink
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteModifierKey
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteOneShotKey
import com.agentsanywhere.app.feature.remotedesktop.input.PointerButton
import com.agentsanywhere.app.feature.remotedesktop.input.RemotePointerMode
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteViewport
import com.agentsanywhere.app.feature.remotedesktop.input.aspectFitViewport
import com.agentsanywhere.app.feature.remotedesktop.input.panBy
import com.agentsanywhere.app.feature.remotedesktop.input.remoteKeyModifier
import com.agentsanywhere.app.feature.remotedesktop.input.remoteKeyboardCode
import com.agentsanywhere.app.feature.remotedesktop.input.zoomBy
import com.agentsanywhere.app.feature.remotedesktop.livekit.RemoteDesktopVideoSurface
import com.agentsanywhere.app.ui.designsystem.AAToastHost
import com.agentsanywhere.app.ui.designsystem.AAToastVisuals
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import io.livekit.android.renderer.TextureViewRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToInt

/** Overlay shown after this long in a reconnect (`docs/remote-desktop.md` section 8.3). */
const val REMOTE_DESKTOP_RECONNECTING_HINT_MILLIS: Long = 15_000L

/**
 * Everything the viewer draws, with no controller and no LiveKit object in sight.
 *
 * The route in `app/` owns the session; this screen is a pure function of [RemoteDesktopUiState]
 * plus the media seam, which is what lets the androidTest render `AwaitingConsent` and
 * `Failed` without a room.
 */
data class RemoteDesktopUiState(
    val deviceName: String,
    val session: RemoteDesktopSessionState = RemoteDesktopSessionState.Idle,
    /** True while this client owns the controller lease and may publish input. */
    val controlling: Boolean = false,
    /** `session.controllerLeaseExpiresAt`, used for the countdown next to the control bar. */
    val leaseExpiresAt: String? = null,
    /** Latest host/RTP sample: actual encoded size and bitrate, not just the preset. */
    val stats: RemoteDesktopStats? = null,
    val quality: RemoteDesktopQualityPreset = RemoteDesktopQualityPreset.Default,
    val pointerMode: RemotePointerMode = RemotePointerMode.Direct,
    /** False when the capability probe says the host cannot be watched; the overlay explains. */
    val capabilitiesAvailable: Boolean = true,
    val unavailableReason: RemoteDesktopUnavailableReason? = null,
    /** A transient message (`control.revoked`, an input error) shown as a toast. */
    val notice: String? = null,
) {
    /** Whether the session was created in control mode, so the control bar is meaningful. */
    val controlMode: Boolean
        get() = when (val current = session) {
            is RemoteDesktopSessionState.Live -> current.session.mode == RemoteDesktopMode.Control
            is RemoteDesktopSessionState.ConnectingMedia -> current.session.mode == RemoteDesktopMode.Control
            is RemoteDesktopSessionState.AwaitingConsent -> current.session.mode == RemoteDesktopMode.Control
            else -> false
        }

    val isTerminal: Boolean
        get() = session is RemoteDesktopSessionState.Ended || session is RemoteDesktopSessionState.Failed
}

/**
 * The watching UI of a remote desktop session.
 *
 * Layout: a top bar (device, status, quality menu, end), the aspect-fit video with local
 * pan/zoom and the gesture layer, the overlays of every non-live state, and — while the
 * session is live — the collapsible side drawer with the controller and modifier bars.
 */
@Composable
fun RemoteDesktopScreen(
    state: RemoteDesktopUiState,
    trackSize: RemoteDesktopTrackSize?,
    media: MediaSession?,
    videoSurface: RemoteDesktopVideoSurface?,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
    onTakeControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onScreenshot: () -> Unit,
    screenshotBusy: Boolean = false,
    onQualityChange: (RemoteDesktopQualityPreset) -> Unit,
    onPointerModeChange: (RemotePointerMode) -> Unit = {},
    onNoticeShown: () -> Unit,
    onPauseInput: () -> Unit = {},
    onForeground: () -> Unit = {},
    onBackgroundExpired: () -> Unit = {},
) {
    val colors = LocalAAColors.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val context = androidx.compose.ui.platform.LocalContext.current
    // Landscape is the "enlarge" mode: the top bar is dropped and the system bars hide, so
    // a 16:9 remote screen uses the whole phone display.
    val landscape =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    RemoteDesktopImmersiveBars(hidden = landscape)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val interaction = remember(media) { RemoteDesktopInteraction(media) }
    val policy = remember { RemoteDesktopLifecyclePolicy() }
    var foreground by remember { mutableStateOf(true) }
    var controlsDrawerOpen by remember { mutableStateOf(false) }
    // Kept on the screen, not inside the drawer: the drawer leaves the composition while a
    // session reconnects or the app goes to the background, and the hint must not replay
    // every time the live overlay comes back.
    var controlsHandleHintDone by remember { mutableStateOf(false) }
    BackHandler(enabled = controlsDrawerOpen) { controlsDrawerOpen = false }
    val status = state.session.connectionStatus()
    val leaseSeconds = rememberLeaseSeconds(state.leaseExpiresAt, state.controlling)
    val armedModifiers = interaction.armedModifiers

    LaunchedEffect(interaction, trackSize) { interaction.setFrameSize(trackSize) }
    LaunchedEffect(state.pointerMode) { interaction.applyPointerMode(state.pointerMode) }

    // Releasing control must also release the local gesture state: a drag lock or a
    // finger-down left button that outlives the lease makes the next takeover start
    // with a stuck button.
    LaunchedEffect(state.controlling) {
        if (!state.controlling) interaction.resetForControlChange()
    }

    LaunchedEffect(state.notice) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            AAToastVisuals(message = notice, isError = true, duration = SnackbarDuration.Long),
        )
        onNoticeShown()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, policy) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    foreground = false
                    if (RemoteDesktopLifecycleAction.ReleaseInput in policy.onStop()) {
                        interaction.releaseForPause()
                        onPauseInput()
                    }
                }

                Lifecycle.Event.ON_START -> {
                    foreground = true
                    val actions = policy.onStart()
                    when {
                        RemoteDesktopLifecycleAction.EndSession in actions -> onBackgroundExpired()
                        RemoteDesktopLifecycleAction.ReattachRenderer in actions -> onForeground()
                    }
                }

                Lifecycle.Event.ON_PAUSE -> {
                    if (RemoteDesktopLifecycleAction.ReleaseInput in policy.onPauseInput()) {
                        interaction.releaseForPause()
                        onPauseInput()
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            policy.reset()
        }
    }

    fun showKeyboard() {
        focusRequester.requestFocus()
        // The IME needs the field's input session, and asking for it in the same frame as
        // the focus change is a no-op on some Android versions: the keyboard then never
        // appears and every key press is lost. Waiting one frame makes it reliable.
        scope.launch {
            withFrameNanos { }
            keyboardController?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (!landscape) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars),
                ) {
                    RemoteDesktopTopBar(
                        deviceName = state.deviceName,
                        status = status,
                        quality = state.quality,
                        stats = state.stats,
                        onBack = onBack,
                        onQualityChange = onQualityChange,
                        onEnd = onEnd,
                        onToggleFullscreen = { context.requestViewerOrientation(landscape = true) },
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                RemoteDesktopVideoCanvas(
                    interaction = interaction,
                    trackSize = trackSize,
                    videoSurface = videoSurface,
                    rendering = foreground,
                    interactive = foreground && !state.isTerminal,
                    showTrackpad = state.controlling,
                    pointerMode = state.pointerMode,
                    onShowKeyboard = ::showKeyboard,
                )
                if (landscape) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(10.dp),
                    ) {
                        RemoteDesktopRoundButton(
                            icon = Lucide.Minimize,
                            description = stringResource(R.string.remote_desktop_exit_fullscreen),
                            onClick = { context.requestViewerOrientation(landscape = false) },
                        )
                    }
                }
                if (foreground && !state.isTerminal && state.session is RemoteDesktopSessionState.Live) {
                    RemoteDesktopControlsDrawer(
                        open = controlsDrawerOpen,
                        onOpenChange = { controlsDrawerOpen = it },
                        showControlBar = state.controlMode,
                        controlling = state.controlling,
                        leaseSeconds = leaseSeconds,
                        pointerMode = state.pointerMode,
                        onPointerModeChange = onPointerModeChange,
                        onTakeControl = onTakeControl,
                        onReleaseControl = onReleaseControl,
                        onScreenshot = onScreenshot,
                        screenshotBusy = screenshotBusy,
                        dragLocked = interaction.dragLocked,
                        armedModifiers = armedModifiers,
                        onModifier = { modifier -> interaction.toggleModifier(modifier) },
                        onOneShot = { key -> interaction.sendOneShot(key) },
                        onKeyboard = ::showKeyboard,
                        controlsHandleHintDone = controlsHandleHintDone,
                        onControlsHandleHintDone = { controlsHandleHintDone = true },
                        modifier = Modifier.align(Alignment.CenterStart).fillMaxSize(),
                    )
                }
                RemoteDesktopStateOverlay(
                    state = state,
                    onBack = onBack,
                    onRetry = onRetry,
                    onEnd = onEnd,
                )
            }
        }
        RemoteDesktopKeyboardField(
            focusRequester = focusRequester,
            onText = { text -> interaction.dispatcher.sendText(text) },
            onKey = { event -> interaction.handleKeyEvent(event) },
            modifier = Modifier.align(Alignment.TopStart),
        )
        AAToastHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp, start = 18.dp, end = 18.dp),
        )
    }
}

@Composable
private fun DragLockPill() {
    val colors = LocalAAColors.current
    Text(
        text = stringResource(R.string.remote_desktop_drag_locked),
        color = colors.onPrimaryAction,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.primaryAction)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * A side drawer for the control and modifier bars. Hidden, it is only the translucent icon
 * button on the left edge; open, it slides in from the left and its header has a close
 * button. This keeps the bottom of the video free for the desktop and the trackpad.
 */
@Composable
private fun RemoteDesktopControlsDrawer(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    showControlBar: Boolean,
    controlling: Boolean,
    leaseSeconds: Int?,
    pointerMode: RemotePointerMode,
    onPointerModeChange: (RemotePointerMode) -> Unit,
    onTakeControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onScreenshot: () -> Unit,
    screenshotBusy: Boolean,
    dragLocked: Boolean,
    armedModifiers: List<RemoteModifierKey>,
    onModifier: (RemoteModifierKey) -> Unit,
    onOneShot: (RemoteOneShotKey) -> Unit,
    onKeyboard: () -> Unit,
    controlsHandleHintDone: Boolean,
    onControlsHandleHintDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    val shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        // `widthIn` after `fillMaxWidth` would be constrained back to the fraction, so
        // compute the cap from the actual incoming width instead.
        val drawerWidth = minOf(maxWidth * 0.56f, 260.dp)
        if (!open) {
            // A compact translucent icon instead of a theme-coloured one: the old 34 dp
            // circle took its colours from the theme, so in the light theme it was a
            // near-black dot on an often dark remote desktop and effectively invisible.
            Box(Modifier.padding(start = 4.dp)) {
                RemoteDesktopControlsHandle(
                    onClick = {
                        onControlsHandleHintDone()
                        onOpenChange(true)
                    },
                    hint = !controlsHandleHintDone,
                    onHintFinished = onControlsHandleHintDone,
                )
            }
        }
        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .testTag("remote-desktop-controls-drawer")
                    .clip(shape)
                    .background(colors.raisedSurface.copy(alpha = 0.98f))
                    .border(1.dp, colors.border, shape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.remote_desktop_controls_title),
                        color = colors.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    RemoteDesktopRoundButton(
                        icon = Lucide.ChevronLeft,
                        description = stringResource(R.string.remote_desktop_controls_hide),
                        onClick = { onOpenChange(false) },
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (dragLocked) DragLockPill()
                    if (showControlBar) {
                        RemoteDesktopControlBar(
                            controlling = controlling,
                            leaseSeconds = leaseSeconds,
                            pointerMode = pointerMode,
                            onPointerModeChange = onPointerModeChange,
                            onTakeControl = onTakeControl,
                            onReleaseControl = onReleaseControl,
                            onScreenshot = onScreenshot,
                            screenshotBusy = screenshotBusy,
                        )
                    }
                    RemoteDesktopModifierBar(
                        armed = armedModifiers,
                        onModifier = onModifier,
                        onOneShot = onOneShot,
                        onKeyboard = onKeyboard,
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteDesktopStateOverlay(
    state: RemoteDesktopUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onEnd: () -> Unit,
) {
    if (!state.capabilitiesAvailable) {
        RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = stringResource(R.string.remote_desktop_unavailable_title),
                body = remoteDesktopUnavailableReasonText(state.unavailableReason),
                primaryLabel = stringResource(R.string.remote_desktop_retry),
                onPrimary = onRetry,
                secondaryLabel = stringResource(R.string.remote_desktop_close),
                onSecondary = onBack,
                isError = true,
            )
        }
        return
    }
    when (val session = state.session) {
        RemoteDesktopSessionState.Idle,
        RemoteDesktopSessionState.Creating,
        -> RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = stringResource(R.string.remote_desktop_connecting),
                busy = true,
            )
        }

        is RemoteDesktopSessionState.ConnectingMedia -> RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = stringResource(R.string.remote_desktop_connecting),
                body = stringResource(R.string.remote_desktop_connecting_body),
                busy = true,
            )
        }

        is RemoteDesktopSessionState.AwaitingConsent -> RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = stringResource(R.string.remote_desktop_awaiting_title),
                body = stringResource(R.string.remote_desktop_awaiting_body, state.deviceName),
                primaryLabel = stringResource(R.string.remote_desktop_cancel),
                onPrimary = onEnd,
            )
        }

        is RemoteDesktopSessionState.Reconnecting -> {
            var slow by remember(session.reason) { mutableStateOf(false) }
            LaunchedEffect(session.reason) {
                slow = false
                delay(REMOTE_DESKTOP_RECONNECTING_HINT_MILLIS)
                slow = true
            }
            RemoteDesktopScrim {
                RemoteDesktopOverlayCard(
                    title = stringResource(R.string.remote_desktop_reconnecting),
                    body = if (slow) stringResource(R.string.remote_desktop_reconnecting_body) else null,
                    busy = true,
                    primaryLabel = stringResource(R.string.remote_desktop_close),
                    onPrimary = onBack,
                )
            }
        }

        is RemoteDesktopSessionState.Live -> Unit

        is RemoteDesktopSessionState.Ended -> RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = stringResource(R.string.remote_desktop_ended_title),
                body = remoteDesktopEndReasonText(session.reason),
                primaryLabel = stringResource(R.string.remote_desktop_close),
                onPrimary = onBack,
            )
        }

        is RemoteDesktopSessionState.Failed -> RemoteDesktopScrim {
            RemoteDesktopOverlayCard(
                title = remoteDesktopFailureText(session),
                body = remoteDesktopFailureBody(session),
                primaryLabel = stringResource(R.string.remote_desktop_retry),
                onPrimary = onRetry,
                secondaryLabel = stringResource(R.string.remote_desktop_close),
                onSecondary = onBack,
                isError = true,
            )
        }
    }
}

@Composable
private fun RemoteDesktopVideoCanvas(
    interaction: RemoteDesktopInteraction,
    trackSize: RemoteDesktopTrackSize?,
    videoSurface: RemoteDesktopVideoSurface?,
    rendering: Boolean,
    interactive: Boolean,
    showTrackpad: Boolean,
    pointerMode: RemotePointerMode,
    onShowKeyboard: () -> Unit,
) {
    val colors = LocalAAColors.current
    val density = LocalDensity.current
    // The fit is derived from the measured canvas and the encoded frame size; reading both
    // from state means a rotation or a resolution change re-fits without extra bookkeeping.
    val fit = interaction.fittedViewport()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // Letterboxing is part of the video surface, not a white panel: a light
            // canvas made the empty right/bottom strip look like a mouse control area.
            .background(Color.Black)
            .onSizeChanged { size ->
                interaction.canvasWidth = size.width.toFloat()
                interaction.canvasHeight = size.height.toFloat()
            }
            .onGloballyPositioned { coordinates ->
                interaction.canvasBoundsInWindow = coordinates.boundsInWindow()
            }
            // `interaction` is a key: the gesture loop captures it, and a Compose
            // pointerInput block keeps running with the instance it started with until a
            // key changes. Restarting on a new interaction is what keeps touch mapping in
            // step with the frame size (otherwise taps were mapped through the raw canvas,
            // letterboxing included, and landed away from the finger).
            .pointerInput(interactive, videoSurface, interaction) {
                if (!interactive) return@pointerInput
                detectRemoteDesktopGestures(interaction, onShowKeyboard)
            },
    ) {
        if (videoSurface != null && trackSize != null && fit.width > 0f && fit.height > 0f) {
            if (rendering) {
                val width = with(density) { fit.width.toDp() }
                val height = with(density) { fit.height.toDp() }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        // `aspectFitViewport` centers the picture; without this offset the
                        // TextureView sat at the top-left and every letterboxed desktop
                        // looked off-center (and taps on the picture missed it).
                        .offset {
                            IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt())
                        }
                        .size(width = width, height = height)
                        .graphicsLayer {
                            scaleX = interaction.zoom
                            scaleY = interaction.zoom
                            translationX = interaction.offsetX
                            translationY = interaction.offsetY
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                ) {
                    // Fallback first: when the media layer can produce bitmaps the viewer draws
                    // them directly, which works even where the WebRTC GL sink paints nothing.
                    val compatBitmap = videoSurface.compatFrame?.collectAsState()?.value
                    if (compatBitmap != null) {
                        Image(
                            bitmap = compatBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        AndroidView(
                            factory = { context ->
                                TextureViewRenderer(context).also { view ->
                                    videoSurface.initRenderer(view)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            update = { view -> videoSurface.attach(view) },
                            onRelease = { view -> videoSurface.detach(view) },
                        )
                    }
                }
            }
        } else {
            Text(
                text = stringResource(R.string.remote_desktop_waiting_frame),
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
            )
        }
        if (showTrackpad && pointerMode == RemotePointerMode.Mouse) {
            RemoteDesktopTrackpad(
                interaction = interaction,
                onShowKeyboard = onShowKeyboard,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    // The bottom bars moved into the side drawer, so the pad can sit
                    // near the thumb without a large reserved strip.
                    .padding(end = 12.dp, bottom = 24.dp)
                    .zIndex(2f),
            )
        }
    }
}

/** Width of the separate wheel rail beside the touch pad. */
private val TRACKPAD_RAIL_WIDTH = 20.dp

/** Gap between the touch pad and the wheel rail; keeps the two hit areas apart. */
private val TRACKPAD_RAIL_GAP = 8.dp

/** Rail height relative to the square touch pad. */
private const val TRACKPAD_RAIL_HEIGHT_FRACTION = 0.82f

/** Wheel pixels per finger pixel while dragging the scroll rail. */
private const val TRACKPAD_SCROLL_PIXEL_GAIN = 2.4f

/** Send accumulated rail motion once it reaches this many finger pixels. */
private const val TRACKPAD_SCROLL_SEND_THRESHOLD_PX = 4f

/**
 * Hit radius for grabbing the cursor ring, as a fraction of the pad width. The visual
 * ring is 9 dp; 0.15 keeps a few dp of touch tolerance instead of turning the whole
 * centre of the pad into a drag handle.
 */
private const val TRACKPAD_PUCK_GRAB_FRACTION = 0.15f

/** The pad's pressed colour: a light blue that reads clearly over any desktop content. */
private val TrackpadActiveBlue = Color(0xFF9FD8FF)

/**
 * Compact floating trackpad in the bottom-right corner. It is composed above the video
 * content; its own pointerInput consumes the gesture before the parent direct-touch
 * detector sees it, and the parent also has a bounds fallback for renderers that swallow
 * child input.
 *
 * The ring inside the panel is the remote cursor mapped into the panel rectangle: it
 * shows where the real mouse is on the desktop without painting a second cursor over the
 * video itself. Pressing the ring (and not lifting) turns it light blue and holds the
 * left button, so the same finger can drag a window or select text; lifting releases the
 * button and restores the ring. The separate narrow rail beside the pad is the wheel:
 * dragging it up or down scrolls the remote desktop, and a two-finger swipe on the pad
 * does too.
 */
@Composable
private fun RemoteDesktopTrackpad(
    interaction: RemoteDesktopInteraction,
    onShowKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The pointerInput must survive the screen recomposing for an unrelated reason (the
    // lease countdown ticks every second); a new function reference in the key would
    // cancel an in-progress drag and could leave the host's button pressed.
    val latestOnShowKeyboard by rememberUpdatedState(onShowKeyboard)

    DisposableEffect(interaction) {
        onDispose {
            interaction.trackpadBoundsInWindow = null
            interaction.trackpadRailBoundsInWindow = null
            interaction.trackpadPuckPressed = false
            interaction.trackpadScrollActive = false
            val actions = interaction.trackpadGestures.cancel()
            if (actions.isNotEmpty()) {
                dispatchTrackpadActions(
                    interaction = interaction,
                    actions = actions,
                    viewport = RemoteViewport(1f, 1f),
                    onDragLockChange = { interaction.trackpadDragLocked = it },
                )
            }
        }
    }

    BoxWithConstraints(modifier) {
        // Deliberately small: a thumb-sized pad leaves the desktop visible while still
        // giving the pointer enough travel to cross a 1440p screen in two swipes.
        val padWidthDp = (maxWidth * 0.28f).coerceIn(104.dp, 132.dp)
        val railHeightDp = padWidthDp * TRACKPAD_RAIL_HEIGHT_FRACTION
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(padWidthDp)
                    .aspectRatio(1f)
                    .testTag("remote-desktop-trackpad")
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(1.dp, Color.White.copy(alpha = 0.30f), RoundedCornerShape(16.dp))
                    .onGloballyPositioned { coordinates ->
                        interaction.trackpadBoundsInWindow = coordinates.boundsInWindow()
                    }
                    .pointerInput(interaction) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            first.consume()
                            detectTrackpadGestureFromDown(
                                interaction = interaction,
                                first = first,
                                onShowKeyboard = latestOnShowKeyboard,
                                panelLocal = true,
                            )
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // A faint joystick base makes the small square read as a control surface
                    // rather than a floating card.
                    drawCircle(
                        color = Color.White.copy(alpha = 0.05f),
                        radius = size.minDimension * 0.42f,
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.10f),
                        radius = size.minDimension * 0.42f,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.remote_desktop_pointer_mouse),
                            color = Color.White.copy(alpha = 0.92f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (interaction.trackpadDragLocked) {
                            Text(
                                text = stringResource(R.string.remote_desktop_trackpad_locked),
                                color = TrackpadActiveBlue,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Text(
                        text = stringResource(R.string.remote_desktop_trackpad_hint),
                        color = Color.White.copy(alpha = 0.68f),
                        fontSize = 8.sp,
                        lineHeight = 10.sp,
                        maxLines = 2,
                    )
                }
                // The cursor ring lives inside the trackpad, not over the desktop picture.
                // The panel is a mini-map of the remote screen: normalized cursor (0..1)
                // maps to the panel rectangle, so the user always sees where the real mouse is.
                Canvas(Modifier.fillMaxSize()) {
                    val pressed = interaction.trackpadPuckPressed
                    val radius = if (pressed) 10.5.dp.toPx() else 9.dp.toPx()
                    // Keep the ring fully visible when the remote cursor is at an edge; the
                    // clamp is smaller than the grab radius, so the touch target still works.
                    val inset = radius + 2.dp.toPx()
                    val point = Offset(
                        x = (interaction.cursor.x * size.width).coerceIn(inset, size.width - inset),
                        y = (interaction.cursor.y * size.height).coerceIn(inset, size.height - inset),
                    )
                    if (pressed) {
                        drawCircle(
                            color = TrackpadActiveBlue.copy(alpha = 0.22f),
                            radius = 20.dp.toPx(),
                            center = point,
                        )
                        drawCircle(color = TrackpadActiveBlue, radius = radius, center = point)
                        drawCircle(
                            color = Color.White.copy(alpha = 0.90f),
                            radius = radius,
                            center = point,
                            style = Stroke(width = 1.2.dp.toPx()),
                        )
                    } else {
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.55f),
                            radius = radius + 2.dp.toPx(),
                            center = point,
                            style = Stroke(width = 3.5.dp.toPx()),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.95f),
                            radius = radius,
                            center = point,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }

            // The gap is deliberately inert, not a hole into the video: a thumb moving
            // from the pad to the rail must not click the remote desktop underneath.
            Box(
                modifier = Modifier
                    .width(TRACKPAD_RAIL_GAP)
                    .height(padWidthDp)
                    .testTag("remote-desktop-trackpad-gap")
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
            )

            // The wheel rail is a separate hit area with a visible gap, so a finger on
            // the touch pad can never be mistaken for a scroll and vice versa.
            Box(
                modifier = Modifier
                    .width(TRACKPAD_RAIL_WIDTH)
                    .height(railHeightDp)
                    .testTag("remote-desktop-trackpad-rail")
                    .clip(RoundedCornerShape(TRACKPAD_RAIL_WIDTH / 2))
                    .background(Color.Black.copy(alpha = 0.28f))
                    .border(
                        1.dp,
                        Color.White.copy(alpha = 0.30f),
                        RoundedCornerShape(TRACKPAD_RAIL_WIDTH / 2),
                    )
                    .onGloballyPositioned { coordinates ->
                        interaction.trackpadRailBoundsInWindow = coordinates.boundsInWindow()
                    }
                    .pointerInput(interaction) {
                        awaitEachGesture {
                            val first = awaitFirstDown(requireUnconsumed = false)
                            first.consume()
                            detectTrackpadScrollDrag(
                                interaction = interaction,
                                first = first,
                                panelOrigin = Offset.Zero,
                            )
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val active = interaction.trackpadScrollActive
                    val railColor = if (active) {
                        TrackpadActiveBlue
                    } else {
                        Color.White.copy(alpha = 0.62f)
                    }
                    if (active) {
                        drawRoundRect(
                            color = TrackpadActiveBlue.copy(alpha = 0.24f),
                            cornerRadius = CornerRadius(size.width / 2f),
                        )
                    }
                    val centerX = size.width / 2f
                    val chevron = 4.dp.toPx()
                    val top = 14.dp.toPx()
                    val bottom = size.height - 14.dp.toPx()
                    drawLine(railColor, Offset(centerX - chevron, top + 4.dp.toPx()), Offset(centerX, top), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(railColor, Offset(centerX + chevron, top + 4.dp.toPx()), Offset(centerX, top), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(railColor, Offset(centerX - chevron, bottom - 4.dp.toPx()), Offset(centerX, bottom), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    drawLine(railColor, Offset(centerX + chevron, bottom - 4.dp.toPx()), Offset(centerX, bottom), strokeWidth = 1.8.dp.toPx(), cap = StrokeCap.Round)
                    val grip = 8.dp.toPx()
                    repeat(3) { index ->
                        val y = size.height / 2f + (index - 1) * 5.dp.toPx()
                        drawLine(
                            railColor.copy(alpha = 0.8f),
                            Offset(centerX - grip / 2f, y),
                            Offset(centerX + grip / 2f, y),
                            strokeWidth = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                        )
                    }
                }
            }
        }
    }
}

private fun dispatchTrackpadActions(
    interaction: RemoteDesktopInteraction,
    actions: List<RemoteGestureAction>,
    viewport: RemoteViewport,
    onDragLockChange: (Boolean) -> Unit,
    onShowKeyboard: () -> Unit = {},
) {
    // Use the same dispatcher path as the picture so RemoteClick/PointerDown/Up/Scroll
    // cannot silently fall through when the trackpad machine adds an action later.
    // Two-finger scroll gets the same pixel gain as the wheel rail; without it the two
    // scroll paths feel noticeably different on the same small panel.
    val scaled = actions.map { action ->
        if (action is RemoteGestureAction.Scroll) {
            action.copy(
                dx = action.dx * TRACKPAD_SCROLL_PIXEL_GAIN,
                dy = action.dy * TRACKPAD_SCROLL_PIXEL_GAIN,
            )
        } else {
            action
        }
    }
    val local = interaction.dispatcher.handle(scaled, viewport, speedGain = true)
    local.forEach { action ->
        when (action) {
            is RemoteGestureAction.DragLock -> onDragLockChange(action.locked)
            RemoteGestureAction.ShowKeyboard -> onShowKeyboard()
            // Local zoom/pan in the small panel has no useful focus point; use the
            // picture centre so pinch still zooms the remote view rather than doing
            // nothing when the user pinches the trackpad.
            is RemoteGestureAction.LocalZoom ->
                interaction.applyZoom(action.factor, interaction.canvasWidth / 2f, interaction.canvasHeight / 2f)

            is RemoteGestureAction.LocalPan ->
                interaction.applyPan(action.dx, action.dy)

            else -> Unit
        }
    }
    interaction.syncCursor()
}

/**
 * Continues a gesture that started inside the floating trackpad. Coordinates arriving
 * from the video's pointerInput are in video-local space; the trackpad state machine and
 * its viewport are in panel-local space, so every sample is shifted by the difference
 * between the two window rectangles.
 *
 * The touch pad and the wheel rail have separate pointerInputs and separate hit areas:
 * a down within [TRACKPAD_PUCK_GRAB_FRACTION] of the cursor ring grabs the ring and holds
 * the left button until lift, which is the precise "click and drag" gesture; anywhere
 * else on the pad is the regular trackpad: swipe to move, tap to click. The rail beside
 * the pad emits wheel events and never receives a pointer move.
 */
private suspend fun AwaitPointerEventScope.detectTrackpadGestureFromDown(
    interaction: RemoteDesktopInteraction,
    first: androidx.compose.ui.input.pointer.PointerInputChange,
    onShowKeyboard: () -> Unit,
    panelLocal: Boolean = false,
) {
    val trackpad = interaction.trackpadBoundsInWindow ?: return
    val panelOrigin = if (panelLocal) {
        Offset.Zero
    } else {
        val canvas = interaction.canvasBoundsInWindow ?: return
        Offset(canvas.left - trackpad.left, canvas.top - trackpad.top)
    }
    fun panelPoint(point: Offset): Offset = point + panelOrigin

    val down = panelPoint(first.position)
    val cursorPoint = Offset(
        x = interaction.cursor.x * trackpad.width,
        y = interaction.cursor.y * trackpad.height,
    )
    val grab = (down - cursorPoint).getDistance() <= trackpad.width * TRACKPAD_PUCK_GRAB_FRACTION

    val viewport = RemoteViewport(trackpad.width, trackpad.height)
    val machine = interaction.trackpadGestures
    val onDragLockChange: (Boolean) -> Unit = { interaction.trackpadDragLocked = it }
    fun dispatch(actions: List<RemoteGestureAction>) {
        dispatchTrackpadActions(
            interaction = interaction,
            actions = actions,
            viewport = viewport,
            onDragLockChange = onDragLockChange,
            onShowKeyboard = onShowKeyboard,
        )
    }

    interaction.trackpadPuckPressed = grab

    var count = 1
    var completed = false
    // Instrumentation events may carry a zero uptime; using it as the long-press
    // baseline made every injected trackpad touch a right click immediately.
    val now = SystemClock.uptimeMillis()
    // Instrumentation events can carry a small synthetic uptime (e.g. 16) while the
    // device clock is far ahead; treating it as a real timestamp makes the long-press
    // timer fire immediately and every injected swipe becomes a right click.
    var downTime = if (first.uptimeMillis <= 0L || now - first.uptimeMillis > 1_000L) {
        now
    } else {
        first.uptimeMillis
    }
    // Grabbing the ring means the button is already down; a long press must not turn
    // into a right click halfway through the drag.
    var longPressArmed = !grab
    try {
        dispatch(machine.down(down.x, down.y, first.uptimeMillis, count, holdButton = grab))

        while (true) {
            val event = if (longPressArmed) {
                val remaining = downTime + machine.longPressMillis - SystemClock.uptimeMillis()
                if (remaining <= 0L) {
                    dispatch(machine.longPress(SystemClock.uptimeMillis()))
                    longPressArmed = false
                    continue
                }
                withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                dispatch(machine.longPress(SystemClock.uptimeMillis()))
                longPressArmed = false
                continue
            }

            // Capture movement before consuming: after consume(), positionChanged() is false
            // on some Compose versions, which made every swipe look like a tap. Historical
            // samples count too: a fast flick can return to its previous position within one
            // frame while still having real motion to replay.
            val moved = event.changes.any { it.positionChanged() || it.historical.isNotEmpty() }
            event.changes.forEach { it.consume() }
            val pressed = event.changes.filter { it.pressed }
            val time = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()
            if (pressed.isEmpty()) {
                val last = event.changes.firstOrNull { it.id == first.id } ?: event.changes.first()
                val point = panelPoint(last.position)
                dispatch(machine.up(point.x, point.y, last.uptimeMillis, count))
                completed = true
                break
            }

            val local = pressed.map { it.position + panelOrigin }
            val total = local.fold(Offset.Zero) { acc, point -> acc + point }
            val centroid = Offset(total.x / local.size, total.y / local.size)
            val spread = if (local.size == 2) {
                (local[0] - local[1]).getDistance()
            } else {
                0f
            }
            if (pressed.size != count) {
                count = pressed.size
                downTime = time
                // A second finger turns the gesture into a two-finger scroll/pinch;
                // the ring is no longer being held by the user.
                interaction.trackpadPuckPressed = false
                // Re-anchor instead of starting a new gesture: starting over would clear
                // "moved" and turn the last finger of a two-finger scroll into a click.
                dispatch(machine.move(centroid.x, centroid.y, time, count, spread))
                longPressArmed = count == 1 && !machine.isMultiFinger
                continue
            }
            if (moved) {
                // Compose batches pointer samples per frame. Replaying the historical
                // positions keeps a fast flick smooth instead of making the cursor jump
                // once per frame, which is the "跟手" difference on a small pad.
                val actions = mutableListOf<RemoteGestureAction>()
                if (count == 1 && pressed.size == 1) {
                    pressed.first().historical.forEach { historical ->
                        val point = panelPoint(historical.position)
                        actions += machine.move(point.x, point.y, historical.uptimeMillis, 1, 0f)
                    }
                }
                actions += machine.move(centroid.x, centroid.y, time, count, spread)
                dispatch(actions)
                if (machine.hasMoved) longPressArmed = false
            }
        }
    } finally {
        interaction.trackpadPuckPressed = false
        // A cancelled pointerInput (viewer disposed, control lost, mode switch) must not
        // leave the host's left button held or the panel's drag-lock pill lying. A gesture
        // that ended with `up()` has already published its own actions, including the
        // drag lock; cancelling it here would immediately release that lock.
        if (!completed) {
            val actions = machine.cancel()
            if (actions.isNotEmpty()) dispatch(actions)
            interaction.trackpadDragLocked = false
        }
    }
}

/**
 * Wheel scrolling from the trackpad's right rail. The finger drag is scaled to remote
 * pixels so a short thumb stroke moves a useful amount, and samples are accumulated to
 * stay inside the 120 events/second input budget instead of being dropped by the throttle.
 */
private suspend fun AwaitPointerEventScope.detectTrackpadScrollDrag(
    interaction: RemoteDesktopInteraction,
    first: androidx.compose.ui.input.pointer.PointerInputChange,
    panelOrigin: Offset,
) {
    interaction.trackpadScrollActive = true
    var lastY = first.position.y + panelOrigin.y
    var pending = 0f
    fun flush() {
        if (pending == 0f) return
        if (interaction.dispatcher.scroll(0f, pending * TRACKPAD_SCROLL_PIXEL_GAIN)) {
            pending = 0f
        }
    }
    try {
        while (true) {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) {
                flush()
                break
            }
            val change = pressed.firstOrNull { it.id == first.id }
            if (change == null) {
                // The initiating finger lifted while a second one stayed on the rail.
                // Re-anchor instead of jumping from one finger's y to the other's.
                val next = pressed.first()
                flush()
                lastY = next.position.y + panelOrigin.y
                continue
            }
            val y = change.position.y + panelOrigin.y
            pending += y - lastY
            lastY = y
            if (abs(pending) >= TRACKPAD_SCROLL_SEND_THRESHOLD_PX) flush()
        }
    } finally {
        // If the throttle was full at lift, try once more; otherwise the pending
        // distance stays lost rather than silently reporting a shorter scroll.
        flush()
        interaction.trackpadScrollActive = false
    }
}

/**
 * Gesture recognition for the canvas.
 *
 * One `awaitEachGesture` loop feeds the pure [RemoteGestureStateMachine]; the long press is
 * armed only while a single finger has not moved, and its timeout is the machine's 500 ms.
 */
private suspend fun PointerInputScope.detectRemoteDesktopGestures(
    interaction: RemoteDesktopInteraction,
    onShowKeyboard: () -> Unit,
) {
    val machine = interaction.gestures
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        // The trackpad usually consumes its own touch, but an AndroidView video renderer
        // can sit above a Compose sibling in the view hierarchy. Keep this bounds-based
        // fallback so a panel touch never falls through to the direct-touch machine.
        if (first.isConsumed) return@awaitEachGesture
        if (interaction.trackpadRailContains(first.position)) {
            first.consume()
            detectTrackpadScrollDrag(interaction, first, interaction.trackpadRailOrigin())
            return@awaitEachGesture
        }
        if (interaction.trackpadContains(first.position)) {
            first.consume()
            detectTrackpadGestureFromDown(interaction, first, onShowKeyboard, panelLocal = false)
            return@awaitEachGesture
        }
        var count = 1
        val now = SystemClock.uptimeMillis()
        var downTime = if (first.uptimeMillis <= 0L || now - first.uptimeMillis > 1_000L) {
            now
        } else {
            first.uptimeMillis
        }
        var longPressArmed = true
        var completed = false
        try {
            dispatchGestureActions(
                interaction,
                machine.down(first.position.x, first.position.y, first.uptimeMillis, count),
                onShowKeyboard,
            )

            while (true) {
                val event = if (longPressArmed) {
                    val remaining = downTime + machine.longPressMillis - SystemClock.uptimeMillis()
                    if (remaining <= 0L) {
                        dispatchGestureActions(interaction, machine.longPress(SystemClock.uptimeMillis()), onShowKeyboard)
                        longPressArmed = false
                        continue
                    }
                    withTimeoutOrNull(remaining) { awaitPointerEvent() }
                } else {
                    awaitPointerEvent()
                }
                if (event == null) {
                    dispatchGestureActions(interaction, machine.longPress(SystemClock.uptimeMillis()), onShowKeyboard)
                    longPressArmed = false
                    continue
                }

                val pressed = event.changes.filter { it.pressed }
                val time = event.changes.firstOrNull()?.uptimeMillis ?: SystemClock.uptimeMillis()
                if (pressed.isEmpty()) {
                    val last = event.changes.firstOrNull { it.id == first.id } ?: event.changes.first()
                    dispatchGestureActions(
                        interaction,
                        machine.up(last.position.x, last.position.y, last.uptimeMillis, count),
                        onShowKeyboard,
                    )
                    completed = true
                    break
                }

                val total = pressed.fold(Offset.Zero) { acc, change -> acc + change.position }
                val centroid = Offset(total.x / pressed.size, total.y / pressed.size)
                val spread = if (pressed.size == 2) {
                    (pressed[0].position - pressed[1].position).getDistance()
                } else {
                    0f
                }
                if (pressed.size != count) {
                    count = pressed.size
                    downTime = time
                    // `move` re-anchors and releases a left button held by a one-finger
                    // drag when a second finger joins, without forgetting that the
                    // gesture already moved. A fresh `down` made the last finger click.
                    dispatchGestureActions(
                        interaction,
                        machine.move(centroid.x, centroid.y, time, count, spread),
                        onShowKeyboard,
                    )
                    longPressArmed = count == 1 && !machine.isMultiFinger
                    continue
                }
                if (pressed.any { it.positionChanged() }) {
                    dispatchGestureActions(
                        interaction,
                        machine.move(centroid.x, centroid.y, time, count, spread),
                        onShowKeyboard,
                    )
                    if (machine.hasMoved) longPressArmed = false
                }
            }
        } finally {
            // A cancelled pointerInput (session ending, background, renderer change) must
            // not leave the host's left button held. Do not cancel a gesture that ended
            // with `up()`: its drag-lock actions are already on the wire.
            if (!completed) {
                val actions = machine.cancel()
                if (actions.isNotEmpty()) dispatchGestureActions(interaction, actions, onShowKeyboard)
                interaction.dragLocked = false
            }
        }
    }
}

private fun dispatchGestureActions(
    interaction: RemoteDesktopInteraction,
    actions: List<RemoteGestureAction>,
    onShowKeyboard: () -> Unit,
) {
    if (actions.isEmpty()) return
    val local = interaction.dispatcher.handle(actions, interaction.effectiveViewport())
    interaction.syncCursor()
    local.forEach { action ->
        when (action) {
            is RemoteGestureAction.LocalZoom ->
                interaction.applyZoom(action.factor, action.focusX, action.focusY)

            is RemoteGestureAction.LocalPan -> interaction.applyPan(action.dx, action.dy)

            is RemoteGestureAction.DragLock -> interaction.dragLocked = action.locked

            RemoteGestureAction.ShowKeyboard -> onShowKeyboard()

            else -> Unit
        }
    }
}

/** Mutable viewer state: the local transform, the cursor and the modifier bar. */
private class RemoteDesktopInteraction(media: MediaSession?) {
    val dispatcher = RemoteInputDispatcher(
        sink = media ?: NoopInputSink,
        log = { message -> android.util.Log.i("RDInput", message) },
    )
    val gestures = RemoteGestureStateMachine()

    var canvasWidth by mutableFloatStateOf(0f)
    var canvasHeight by mutableFloatStateOf(0f)

    /** Encoded size of the subscribed track, mirrored from [RemoteDesktopTrackSize]. */
    private var frameWidth by mutableIntStateOf(0)
    private var frameHeight by mutableIntStateOf(0)

    /** Local magnification and offset relative to the aspect-fitted picture. */
    var zoom by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    var dragLocked by mutableStateOf(false)
    var pointerMode by mutableStateOf(RemotePointerMode.Direct)
        private set
    /** Local cursor position in remote normalized space, for the trackpad overlay. */
    var cursor by mutableStateOf(Offset(0.5f, 0.5f))
        private set
    /** The video's bounds and the floating trackpad's bounds, both in window coordinates. */
    var canvasBoundsInWindow by mutableStateOf<Rect?>(null)
    var trackpadBoundsInWindow by mutableStateOf<Rect?>(null)
    /** The separate wheel rail's bounds; kept distinct from the touch pad. */
    var trackpadRailBoundsInWindow by mutableStateOf<Rect?>(null)
    /** Separate mouse-mode recognizer for the floating trackpad gesture. */
    val trackpadGestures = RemoteGestureStateMachine(pointerMode = RemotePointerMode.Mouse)
    var trackpadDragLocked by mutableStateOf(false)
    /** True while a finger holds the cursor ring; drives the light-blue pressed ring. */
    var trackpadPuckPressed by mutableStateOf(false)
    /** True while a finger drags the trackpad's scroll rail. */
    var trackpadScrollActive by mutableStateOf(false)
    var armedModifiers by mutableStateOf(emptyList<RemoteModifierKey>())
        private set

    fun setFrameSize(trackSize: RemoteDesktopTrackSize?) {
        frameWidth = trackSize?.width ?: 0
        frameHeight = trackSize?.height ?: 0
    }

    /** The picture box in view coordinates, before the local zoom/pan. */
    fun fittedViewport(): RemoteViewport = aspectFitViewport(
        viewWidth = canvasWidth,
        viewHeight = canvasHeight,
        imageWidth = frameWidth.toFloat(),
        imageHeight = frameHeight.toFloat(),
    )

    /** Viewport for touch mapping: the fitted box plus the local pan/zoom. */
    fun effectiveViewport(): RemoteViewport {
        val fit = fittedViewport()
        return RemoteViewport(
            width = fit.width,
            height = fit.height,
            scale = zoom,
            offsetX = fit.offsetX + offsetX,
            offsetY = fit.offsetY + offsetY,
        )
    }

    fun applyZoom(factor: Float, focusX: Float, focusY: Float) {
        val fit = fittedViewport()
        val updated = zoomBy(
            viewport = transformViewport(fit),
            factor = factor,
            focusX = focusX - fit.offsetX,
            focusY = focusY - fit.offsetY,
        )
        zoom = updated.scale
        offsetX = updated.offsetX
        offsetY = updated.offsetY
        gestures.setZoomed(zoom > 1.001f)
    }

    fun applyPan(dx: Float, dy: Float) {
        val updated = panBy(transformViewport(fittedViewport()), dx, dy)
        offsetX = updated.offsetX
        offsetY = updated.offsetY
    }

    /**
     * Shows/hides the dedicated trackpad and releases any drag still held by the old
     * gesture. The picture itself always keeps direct-touch semantics; switching to
     * mouse mode does not turn the whole video into a trackpad.
     */
    fun applyPointerMode(mode: RemotePointerMode) {
        if (pointerMode == mode) return
        pointerMode = mode
        dispatcher.setPointerMode(mode)
        val actions = gestures.cancel()
        if (actions.isNotEmpty()) {
            dispatcher.handle(actions, effectiveViewport())
        }
        val trackpadActions = trackpadGestures.cancel()
        if (trackpadActions.isNotEmpty()) {
            dispatchTrackpadActions(
                interaction = this,
                actions = trackpadActions,
                viewport = RemoteViewport(1f, 1f),
                onDragLockChange = { trackpadDragLocked = it },
            )
        }
        dragLocked = gestures.isDragLocked
        trackpadDragLocked = false
        trackpadPuckPressed = false
        trackpadScrollActive = false
        syncCursor()
    }

    fun syncCursor() {
        cursor = Offset(dispatcher.cursorX, dispatcher.cursorY)
    }

    /** True when a video-local gesture point falls inside either trackpad hit area. */
    fun trackpadContains(point: Offset): Boolean {
        val canvas = canvasBoundsInWindow ?: return false
        val windowPoint = Offset(canvas.left + point.x, canvas.top + point.y)
        return trackpadBoundsInWindow?.contains(windowPoint) == true ||
            trackpadRailBoundsInWindow?.contains(windowPoint) == true
    }

    /** True when a video-local point is on the wheel rail, not the touch pad. */
    fun trackpadRailContains(point: Offset): Boolean {
        val canvas = canvasBoundsInWindow ?: return false
        val rail = trackpadRailBoundsInWindow ?: return false
        return rail.contains(Offset(canvas.left + point.x, canvas.top + point.y))
    }

    /** Video-local -> rail-local offset for the parent pointerInput fallback. */
    fun trackpadRailOrigin(): Offset {
        val canvas = canvasBoundsInWindow ?: return Offset.Zero
        val rail = trackpadRailBoundsInWindow ?: return Offset.Zero
        return Offset(canvas.left - rail.left, canvas.top - rail.top)
    }

    /**
     * The viewer is going to the background or the screen is locking. Publish an empty
     * pointer frame (and release_all if a button is locally held) before the lifecycle
     * callback; the frame's newer sequence fences drag frames that are still in flight
     * on the lossy channel.
     */
    fun releaseForPause() {
        // Cancel the pure gesture machines too. Without this, a drag lock or a held
        // drag survives into the resumed session and the first tap is swallowed.
        val gestureActions = gestures.cancel()
        if (gestureActions.isNotEmpty()) {
            dispatcher.handle(gestureActions, effectiveViewport())
        }
        val trackpadActions = trackpadGestures.cancel()
        if (trackpadActions.isNotEmpty()) {
            dispatchTrackpadActions(
                interaction = this,
                actions = trackpadActions,
                viewport = RemoteViewport(1f, 1f),
                onDragLockChange = { trackpadDragLocked = it },
            )
        }
        trackpadPuckPressed = false
        trackpadScrollActive = false
        dispatcher.releaseAll(RELEASE_REASON_PAGE_HIDE)
        dragLocked = false
        trackpadDragLocked = false
        syncCursor()
    }

    /** Clears a drag lock and any locally held button after control ownership changes. */
    fun resetForControlChange() {
        val actions = gestures.cancel()
        if (actions.isNotEmpty()) {
            dispatcher.handle(actions, effectiveViewport())
        }
        val trackpadActions = trackpadGestures.cancel()
        if (trackpadActions.isNotEmpty()) {
            dispatchTrackpadActions(
                interaction = this,
                actions = trackpadActions,
                viewport = RemoteViewport(1f, 1f),
                onDragLockChange = { trackpadDragLocked = it },
            )
        }
        trackpadDragLocked = false
        trackpadPuckPressed = false
        trackpadScrollActive = false
        // Belt and braces: the gesture machine only knows its own drag, while the
        // dispatcher can also hold a button from the drag-lock toggle.
        dispatcher.releaseAll(RELEASE_REASON_HANDOFF)
        dragLocked = false
        syncCursor()
    }

    fun toggleModifier(modifier: RemoteModifierKey) {
        armedModifiers = dispatcher.modifiers.toggle(modifier)
    }

    fun sendOneShot(key: RemoteOneShotKey) {
        dispatcher.sendOneShot(key.wireCode)
        armedModifiers = dispatcher.modifiers.active
    }

    /** Hardware and IME key events; Backspace/Enter/arrows travel as `key` events. */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val modifier = remoteKeyModifier(native.keyCode)
        if (event.type == KeyEventType.KeyDown) {
            if (modifier != null) {
                // A hardware modifier press arms the sticky bar instead of forming a chord,
                // the same rule the on-screen bar follows.
                val armed = RemoteModifierKey.entries.firstOrNull { it.wireValue == modifier } ?: return true
                armedModifiers = dispatcher.modifiers.toggle(armed)
                return true
            }
            val code = remoteKeyboardCode(native.keyCode) ?: return false
            dispatcher.sendKey(code, down = true, repeat = native.repeatCount > 0)
            armedModifiers = dispatcher.modifiers.active
            return true
        }
        if (event.type == KeyEventType.KeyUp && modifier == null) {
            val code = remoteKeyboardCode(native.keyCode) ?: return false
            dispatcher.sendKey(code, down = false)
            return true
        }
        return false
    }

    private fun transformViewport(fit: RemoteViewport): RemoteViewport = RemoteViewport(
        width = fit.width,
        height = fit.height,
        scale = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
    )
}

private object NoopInputSink : RemoteInputSink {
    override fun sendInput(json: String) = Unit

    override fun sendPointerFrame(bytes: ByteArray) = Unit
}

private fun RemoteDesktopSessionState.connectionStatus(): RemoteDesktopConnectionStatus = when (this) {
    RemoteDesktopSessionState.Idle,
    RemoteDesktopSessionState.Creating,
    is RemoteDesktopSessionState.ConnectingMedia,
    -> RemoteDesktopConnectionStatus.Connecting

    is RemoteDesktopSessionState.AwaitingConsent -> RemoteDesktopConnectionStatus.AwaitingConsent
    is RemoteDesktopSessionState.Live -> RemoteDesktopConnectionStatus.Live
    is RemoteDesktopSessionState.Reconnecting -> RemoteDesktopConnectionStatus.Reconnecting
    is RemoteDesktopSessionState.Ended -> RemoteDesktopConnectionStatus.Ended
    is RemoteDesktopSessionState.Failed -> RemoteDesktopConnectionStatus.Failed
}

/** Remaining controller-lease seconds, ticking once a second while control is held. */
@Composable
private fun rememberLeaseSeconds(leaseExpiresAt: String?, controlling: Boolean): Int? {
    if (!controlling || leaseExpiresAt.isNullOrBlank()) return null
    var seconds by remember(leaseExpiresAt) { mutableStateOf(remainingSeconds(leaseExpiresAt)) }
    LaunchedEffect(leaseExpiresAt) {
        while (true) {
            seconds = remainingSeconds(leaseExpiresAt)
            delay(1_000L)
        }
    }
    return seconds
}

private fun remainingSeconds(expiresAt: String): Int? = runCatching {
    val deadline = Instant.parse(expiresAt)
    val remaining = deadline.toEpochMilli() - System.currentTimeMillis()
    (remaining.coerceAtLeast(0L) / 1_000L).toInt()
}.getOrNull()
