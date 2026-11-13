package com.agentsanywhere.app.ui.screens.remotedesktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.feature.remotedesktop.RemoteDesktopQualityPreset
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteModifierKey
import com.agentsanywhere.app.feature.remotedesktop.input.RemoteOneShotKey
import com.agentsanywhere.app.ui.designsystem.AADropdownMenu
import com.agentsanywhere.app.ui.designsystem.AADropdownMenuItem
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Keyboard
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X

/** Connection state as the badge shows it; derived from the controller state by the screen. */
internal enum class RemoteDesktopConnectionStatus(val labelRes: Int) {
    Connecting(R.string.remote_desktop_status_connecting),
    AwaitingConsent(R.string.remote_desktop_status_awaiting),
    Live(R.string.remote_desktop_status_live),
    Reconnecting(R.string.remote_desktop_status_reconnecting),
    Ended(R.string.remote_desktop_status_ended),
    Failed(R.string.remote_desktop_status_failed),
}

@Composable
internal fun RemoteDesktopTopBar(
    deviceName: String,
    status: RemoteDesktopConnectionStatus,
    quality: RemoteDesktopQualityPreset,
    onBack: () -> Unit,
    onQualityChange: (RemoteDesktopQualityPreset) -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    var qualityMenuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RemoteDesktopRoundButton(
            icon = Lucide.ChevronLeft,
            description = stringResource(R.string.common_back),
            onClick = onBack,
        )
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = deviceName,
                modifier = Modifier.weight(1f, fill = false),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RemoteDesktopStatusBadge(status)
        }
        Box {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.subtle)
                    .noRippleClickable { qualityMenuOpen = true }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.remote_desktop_quality),
                    color = colors.inkSoft,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = qualityLabel(quality),
                    color = colors.ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    tint = colors.inkSoft,
                    modifier = Modifier.size(14.dp),
                )
            }
            AADropdownMenu(
                expanded = qualityMenuOpen,
                onDismissRequest = { qualityMenuOpen = false },
            ) {
                RemoteDesktopQualityPreset.entries.forEach { preset ->
                    AADropdownMenuItem(
                        text = qualityLabel(preset),
                        selected = preset == quality,
                        onClick = {
                            qualityMenuOpen = false
                            onQualityChange(preset)
                        },
                    )
                }
            }
        }
        RemoteDesktopRoundButton(
            icon = Lucide.X,
            description = stringResource(R.string.remote_desktop_end),
            onClick = onEnd,
        )
    }
}

@Composable
internal fun qualityLabel(preset: RemoteDesktopQualityPreset): String = stringResource(
    when (preset) {
        RemoteDesktopQualityPreset.Smooth -> R.string.remote_desktop_quality_smooth
        RemoteDesktopQualityPreset.Clear -> R.string.remote_desktop_quality_clear
        RemoteDesktopQualityPreset.Auto -> R.string.remote_desktop_quality_auto
    },
)

@Composable
internal fun RemoteDesktopStatusBadge(status: RemoteDesktopConnectionStatus) {
    val colors = LocalAAColors.current
    val dot = when (status) {
        RemoteDesktopConnectionStatus.Live -> colors.noticeSuccess
        RemoteDesktopConnectionStatus.Reconnecting -> colors.noticeWarning
        RemoteDesktopConnectionStatus.AwaitingConsent -> colors.sessionStatusAccent
        RemoteDesktopConnectionStatus.Connecting -> colors.muted
        RemoteDesktopConnectionStatus.Ended,
        RemoteDesktopConnectionStatus.Failed,
        -> colors.errorText
    }
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(colors.subtle)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(dot),
        )
        Text(
            text = stringResource(status.labelRes),
            color = colors.inkSoft,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** The floating bar of sticky modifiers plus the two one-shot keys and the keyboard button. */
@Composable
internal fun RemoteDesktopModifierBar(
    armed: List<RemoteModifierKey>,
    onModifier: (RemoteModifierKey) -> Unit,
    onOneShot: (RemoteOneShotKey) -> Unit,
    onKeyboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.raisedSurface.copy(alpha = 0.94f))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RemoteModifierKey.entries.forEach { key ->
            RemoteDesktopKeyChip(
                label = key.label,
                active = key in armed,
                onClick = { onModifier(key) },
            )
        }
        RemoteOneShotKey.entries.forEach { key ->
            RemoteDesktopKeyChip(
                label = key.label,
                active = false,
                onClick = { onOneShot(key) },
            )
        }
        RemoteDesktopRoundButton(
            icon = Lucide.Keyboard,
            description = stringResource(R.string.remote_desktop_keyboard),
            onClick = onKeyboard,
        )
    }
}

@Composable
private fun RemoteDesktopKeyChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    Text(
        text = label,
        color = if (active) colors.onPrimaryAction else colors.ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) colors.primaryAction else colors.subtle)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Control ownership: take over, release, and how long the lease still runs. */
@Composable
internal fun RemoteDesktopControlBar(
    controlling: Boolean,
    leaseSeconds: Int?,
    onTakeControl: () -> Unit,
    onReleaseControl: () -> Unit,
    onScreenshot: () -> Unit,
    screenshotBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(colors.raisedSurface.copy(alpha = 0.94f))
            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (controlling) {
                stringResource(R.string.remote_desktop_controlling)
            } else {
                stringResource(R.string.remote_desktop_view_only)
            },
            color = colors.inkSoft,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.widthIn(max = 160.dp),
            maxLines = 2,
        )
        if (controlling && leaseSeconds != null) {
            Text(
                text = stringResource(R.string.remote_desktop_lease, leaseSeconds),
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = stringResource(
                if (controlling) R.string.remote_desktop_release_control else R.string.remote_desktop_take_control,
            ),
            color = if (controlling) colors.ink else colors.onPrimaryAction,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(if (controlling) colors.subtle else colors.primaryAction)
                .noRippleClickable(onClick = if (controlling) onReleaseControl else onTakeControl)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        RemoteDesktopRoundButton(
            icon = Lucide.Camera,
            description = stringResource(R.string.remote_desktop_screenshot),
            onClick = { if (!screenshotBusy) onScreenshot() },
        )
    }
}

@Composable
internal fun RemoteDesktopRoundButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
) {
    val colors = LocalAAColors.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (highlighted) colors.primaryAction else Color.Transparent)
            .noRippleClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (highlighted) colors.onPrimaryAction else colors.ink,
            modifier = Modifier.size(19.dp),
        )
    }
}

/**
 * Invisible IME sink: committed text goes to `text` events, hardware and IME key presses go
 * to `key` events. Placed at 1 dp so the field can hold focus without being seen; the
 * viewer summons it with the keyboard button or a three-finger swipe.
 *
 * Compose Foundation resolves to 1.8.x here (coil pulls it in), whose value-based
 * `BasicTextField` no longer takes `onPreviewKeyEvent`; the key handler therefore sits on the
 * modifier, which is also where it sees Backspace/Enter/arrow keys from a soft keyboard.
 */
@Composable
internal fun RemoteDesktopKeyboardField(
    focusRequester: FocusRequester,
    onText: (String) -> Unit,
    onKey: (KeyEvent) -> Boolean,
    modifier: Modifier = Modifier,
) {
    // The field is never cleared between commits: it holds the IME composition and is reset
    // as soon as the composition finishes, so a pinyin candidate list is not destroyed.
    var value by remember { mutableStateOf(TextFieldValue("")) }
    BasicTextField(
        value = value,
        onValueChange = { updated ->
            val composing = updated.composition != null
            if (!composing && updated.text.isNotEmpty()) {
                onText(updated.text)
                value = TextFieldValue("")
            } else {
                value = updated
            }
        },
        modifier = modifier
            .size(1.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent(onKey),
        textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.None,
        ),
        keyboardActions = KeyboardActions(),
    )
}
