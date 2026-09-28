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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.menu.DifftDropdownMenu
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItem
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItemTone
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.data.VoicePreset

@Composable
fun VoicePresetCard(
    currentPreset: VoicePreset,
    onPresetSelected: (VoicePreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val menuWidth = 180.dp
    val dropdownOffset = with(density) { boxWidthPx.toDp() } - menuWidth

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { boxWidthPx = it.width }
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
                text = ResUtils.getString(R.string.call_voice_changer_label),
                style = DifftTheme.typography.p2,
                color = DifftTheme.colors.textPrimary,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = currentPreset.displayText(),
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
            modifier = Modifier.width(menuWidth),
            offset = DpOffset(x = dropdownOffset, y = 0.dp)
        ) {
            VoicePreset.entries.forEach { preset ->
                DifftDropdownMenuItem(
                    text = preset.displayText(),
                    tone = DifftDropdownMenuItemTone.selection(preset == currentPreset),
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    }
                )
            }
        }
    }
}
