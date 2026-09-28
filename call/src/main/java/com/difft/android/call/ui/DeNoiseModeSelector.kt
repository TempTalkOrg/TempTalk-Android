package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.menu.DifftDropdownMenu
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItem
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItemTone
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.github.TempTalkOrg.audio_pipeline.AudioModule

@Composable
fun DeNoiseModeSelector(
    currentMode: AudioModule,
    onModeSelected: (AudioModule) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val modeLabel = when (currentMode) {
        AudioModule.DEEP_FILTER_NET ->
            ResUtils.getString(R.string.call_denoise_mode_enhanced)
        else -> ResUtils.getString(R.string.call_denoise_mode_standard)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .background(
                    color = DifftTheme.colors.backgroundTertiary,
                    shape = DifftTheme.radius.largeShape
                )
                .clickable { expanded = true }
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ResUtils.getString(R.string.call_denoise_mode_label),
                style = DifftTheme.typography.p2,
                color = DifftTheme.colors.textPrimary,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = modeLabel,
                    style = DifftTheme.typography.p3,
                    color = DifftTheme.colors.textSecondary,
                )
                Icon(
                    painter = painterResource(id = R.drawable.call_btn_tabler_chevron_right),
                    contentDescription = null,
                    tint = DifftTheme.colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DifftDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DifftDropdownMenuItem(
                text = ResUtils.getString(R.string.call_denoise_mode_standard),
                tone = DifftDropdownMenuItemTone.selection(currentMode == AudioModule.RNNOISE),
                onClick = {
                    expanded = false
                    onModeSelected(AudioModule.RNNOISE)
                }
            )
            DifftDropdownMenuItem(
                text = ResUtils.getString(R.string.call_denoise_mode_enhanced),
                tone = DifftDropdownMenuItemTone.selection(currentMode == AudioModule.DEEP_FILTER_NET),
                onClick = {
                    expanded = false
                    onModeSelected(AudioModule.DEEP_FILTER_NET)
                }
            )
        }
    }
}
