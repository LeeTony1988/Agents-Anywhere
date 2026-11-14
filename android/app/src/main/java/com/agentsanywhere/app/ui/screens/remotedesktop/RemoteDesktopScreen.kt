package com.agentsanywhere.app.ui.screens.remotedesktop

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopMode
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.MediaSession
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopLifecycleAction
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopLifecyclePolicy
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopTrackSize
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteGestureAction
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteGestureStateMachine
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputDispatcher
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteInputSink
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteModifierKey
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteOneShotKey
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
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

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
    val quality: RemoteDesktopQualityPreset = RemoteDesktopQualityPreset.Default,
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
 * session is live — the controller bar plus the sticky modifier bar.
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
    onNoticeShown: () -> Unit,
    onPauseInput: () -> Unit = {},
    onForeground: () -> Unit = {},
    onBackgroundExpired: () -> Unit = {},
) {
    val colors = LocalAAColors.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val snackbarHostState = remember { SnackbarHostState() }
    val interaction = remember(media, trackSize) { RemoteDesktopInteraction(media).also { it.setFrameSize(trackSize) } }
    val policy = remember { RemoteDesktopLifecyclePolicy() }
    var foreground by remember { mutableStateOf(true) }
    val status = state.session.connectionStatus()
    val leaseSeconds = rememberLeaseSeconds(state.leaseExpiresAt, state.controlling)
    val armedModifiers = interaction.armedModifiers

    LaunchedEffect(interaction, trackSize) { interaction.setFrameSize(trackSize) }

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
                    if (RemoteDesktopLifecycleAction.ReleaseInput in policy.onStop()) onPauseInput()
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
                    if (RemoteDesktopLifecycleAction.ReleaseInput in policy.onPauseInput()) onPauseInput()
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
        keyboardController?.show()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                RemoteDesktopTopBar(
                    deviceName = state.deviceName,
                    status = status,
                    quality = state.quality,
                    onBack = onBack,
                    onQualityChange = onQualityChange,
                    onEnd = onEnd,
                )
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
                    onShowKeyboard = ::showKeyboard,
                )
                if (foreground && !state.isTerminal && state.session is RemoteDesktopSessionState.Live) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (interaction.dragLocked) DragLockPill()
                        if (state.controlMode) {
                            RemoteDesktopControlBar(
                                controlling = state.controlling,
                                leaseSeconds = leaseSeconds,
                                onTakeControl = onTakeControl,
                                onReleaseControl = onReleaseControl,
                                onScreenshot = onScreenshot,
                                screenshotBusy = screenshotBusy,
                            )
                        }
                        RemoteDesktopModifierBar(
                            armed = armedModifiers,
                            onModifier = { modifier ->
                                interaction.toggleModifier(modifier)
                            },
                            onOneShot = { key -> interaction.sendOneShot(key) },
                            onKeyboard = ::showKeyboard,
                        )
                    }
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
            .background(colors.canvas)
            .onSizeChanged { size ->
                interaction.canvasWidth = size.width.toFloat()
                interaction.canvasHeight = size.height.toFloat()
            }
            .pointerInput(interactive, videoSurface) {
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
                            contentScale = ContentScale.Crop,
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
        var count = 1
        var downTime = first.uptimeMillis
        var longPressArmed = true
        machine.down(first.position.x, first.position.y, first.uptimeMillis, count)

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
                longPressArmed = count == 1
                machine.down(centroid.x, centroid.y, time, count, spread)
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
    }
}

private fun dispatchGestureActions(
    interaction: RemoteDesktopInteraction,
    actions: List<RemoteGestureAction>,
    onShowKeyboard: () -> Unit,
) {
    if (actions.isEmpty()) return
    val local = interaction.dispatcher.handle(actions, interaction.effectiveViewport())
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
    val dispatcher = RemoteInputDispatcher(media ?: NoopInputSink)
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
