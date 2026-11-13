package com.agentsanywhere.app.ui.screens.remotedesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.api.RemoteDesktopEndReason
import com.agentsanywhere.app.api.RemoteDesktopErrorCode
import com.agentsanywhere.app.api.RemoteDesktopUnavailableReason
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopSessionState
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.designsystem.noRippleClickable

/** Scrim behind a blocking overlay; the picture keeps rendering underneath while connecting. */
@Composable
internal fun RemoteDesktopScrim(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalAAColors.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas.copy(alpha = 0.86f)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** One overlay: a title, a body and up to two actions. */
@Composable
internal fun RemoteDesktopOverlayCard(
    title: String,
    body: String? = null,
    busy: Boolean = false,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    isError: Boolean = false,
) {
    val colors = LocalAAColors.current
    Column(
        modifier = Modifier
            .padding(horizontal = 28.dp)
            .widthIn(max = 420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                color = colors.primaryAction,
                strokeWidth = 2.dp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = title,
            color = colors.ink,
            fontSize = 19.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        body?.takeIf(String::isNotBlank)?.let { text ->
            Text(
                text = text,
                color = if (isError) colors.errorText else colors.inkSoft,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryLabel != null && onPrimary != null) {
            RemoteDesktopOverlayButton(label = primaryLabel, onClick = onPrimary, filled = true)
        }
        if (secondaryLabel != null && onSecondary != null) {
            RemoteDesktopOverlayButton(label = secondaryLabel, onClick = onSecondary, filled = false)
        }
    }
}

@Composable
private fun RemoteDesktopOverlayButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
) {
    val colors = LocalAAColors.current
    val shape = RoundedCornerShape(14.dp)
    Text(
        text = label,
        color = if (filled) colors.onPrimaryAction else colors.ink,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(shape)
            .background(if (filled) colors.primaryAction else colors.subtle)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 11.dp),
    )
}

/** Text of one terminal state, mapped from the stable error/end enums. */
@Composable
internal fun remoteDesktopFailureText(state: RemoteDesktopSessionState.Failed): String =
    when (state.code) {
        RemoteDesktopErrorCode.Unavailable -> stringResource(R.string.remote_desktop_error_unavailable)
        RemoteDesktopErrorCode.Busy -> stringResource(R.string.remote_desktop_error_busy)
        RemoteDesktopErrorCode.LimitReached -> stringResource(R.string.remote_desktop_error_limit)
        RemoteDesktopErrorCode.ConsentPending -> stringResource(R.string.remote_desktop_error_consent_pending)
        RemoteDesktopErrorCode.NotController -> stringResource(R.string.remote_desktop_error_not_controller)
        RemoteDesktopErrorCode.RateLimited -> stringResource(R.string.remote_desktop_error_rate_limited)
        RemoteDesktopErrorCode.NotFound -> stringResource(R.string.remote_desktop_error_not_found)
        RemoteDesktopErrorCode.MediaUnavailable -> stringResource(R.string.remote_desktop_error_media)
        RemoteDesktopErrorCode.ConnectorNotFound -> stringResource(R.string.remote_desktop_error_connector_not_found)
        RemoteDesktopErrorCode.Unknown -> stringResource(R.string.remote_desktop_error_unknown)
    }

/**
 * Body of a failure overlay: the Server's message when it adds detail beyond the mapped
 * code text. Null lets the overlay show the title alone.
 */
@Composable
internal fun remoteDesktopFailureBody(state: RemoteDesktopSessionState.Failed): String? {
    val message = state.message.trim()
    if (message.isEmpty()) return null
    val mapped = remoteDesktopFailureText(state)
    return message.takeIf { it != mapped }
}

@Composable
internal fun remoteDesktopEndReasonText(reason: RemoteDesktopEndReason): String =
    when (reason) {
        RemoteDesktopEndReason.ClientEnded -> stringResource(R.string.remote_desktop_ended_client)
        RemoteDesktopEndReason.HostEnded -> stringResource(R.string.remote_desktop_ended_host)
        RemoteDesktopEndReason.ConsentDenied -> stringResource(R.string.remote_desktop_ended_consent_denied)
        RemoteDesktopEndReason.ConsentTimeout -> stringResource(R.string.remote_desktop_ended_consent_timeout)
        RemoteDesktopEndReason.IdleTimeout -> stringResource(R.string.remote_desktop_ended_idle)
        RemoteDesktopEndReason.DurationLimit -> stringResource(R.string.remote_desktop_ended_duration)
        RemoteDesktopEndReason.ConnectorDeleted -> stringResource(R.string.remote_desktop_ended_connector_deleted)
        RemoteDesktopEndReason.ConnectorOffline -> stringResource(R.string.remote_desktop_ended_connector_offline)
        RemoteDesktopEndReason.MediaError -> stringResource(R.string.remote_desktop_ended_media_error)
        RemoteDesktopEndReason.PolicyRevoked -> stringResource(R.string.remote_desktop_ended_policy)
        RemoteDesktopEndReason.Unknown -> stringResource(R.string.remote_desktop_ended_unknown)
    }

@Composable
internal fun remoteDesktopUnavailableReasonText(reason: RemoteDesktopUnavailableReason?): String =
    when (reason) {
        RemoteDesktopUnavailableReason.ConnectorOffline -> stringResource(R.string.remote_desktop_reason_connector_offline)
        RemoteDesktopUnavailableReason.HostNotRunning -> stringResource(R.string.remote_desktop_reason_host_not_running)
        RemoteDesktopUnavailableReason.HostNotInstalled -> stringResource(R.string.remote_desktop_reason_host_not_installed)
        RemoteDesktopUnavailableReason.ConnectorOutdated -> stringResource(R.string.remote_desktop_reason_connector_outdated)
        RemoteDesktopUnavailableReason.UnsupportedPlatform -> stringResource(R.string.remote_desktop_reason_unsupported)
        RemoteDesktopUnavailableReason.ScreenPermissionDenied -> stringResource(R.string.remote_desktop_reason_screen_permission)
        RemoteDesktopUnavailableReason.AccessibilityPermissionDenied -> stringResource(R.string.remote_desktop_reason_accessibility)
        RemoteDesktopUnavailableReason.DevicePolicyDisabled -> stringResource(R.string.remote_desktop_reason_policy)
        RemoteDesktopUnavailableReason.SessionLimitReached -> stringResource(R.string.remote_desktop_reason_session_limit)
        RemoteDesktopUnavailableReason.MediaNotConfigured -> stringResource(R.string.remote_desktop_reason_media_not_configured)
        RemoteDesktopUnavailableReason.Unknown, null -> stringResource(R.string.remote_desktop_reason_unknown)
    }
