package com.agentsanywhere.app.ui.screens.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentsanywhere.app.R
import com.agentsanywhere.app.ui.designsystem.LocalAAColors
import com.agentsanywhere.app.ui.designsystem.noRippleClickable
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor

/**
 * The "watch this device's screen" entry of the device page.
 *
 * Only composed when the capability probe said the whole remote desktop set is usable
 * (`remoteDesktopEntryVisible`); a device that cannot be watched simply has no row.
 */
@Composable
fun DeviceRemoteDesktopEntry(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.raisedSurface)
            .border(1.dp, colors.border, shape)
            .noRippleClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Lucide.Monitor,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(R.string.remote_desktop_entry),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.remote_desktop_entry_hint),
                color = colors.inkSoft,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp),
        )
    }
}
