package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.difft.android.call.data.VoicePreset

@Composable
fun VoicePresetCard(
    currentPreset: VoicePreset,
    isParticipantSharedScreen: Boolean,
    onPresetSelected: (VoicePreset) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var boxWidthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val menuWidth = 180.dp
    val dropdownOffset = with(density) { boxWidthPx.toDp() } - menuWidth

    Box(
        modifier = Modifier
            .then(if (isParticipantSharedScreen) Modifier.width(343.dp) else Modifier.fillMaxWidth())
            .onSizeChanged { boxWidthPx = it.width }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .background(
                    color = colorResource(id = com.difft.android.base.R.color.gray_600),
                    shape = RoundedCornerShape(size = 8.dp)
                )
                .clickable { expanded = true }
                .padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = ResUtils.getString(R.string.call_voice_changer_label),
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight(400),
                    color = colorResource(id = com.difft.android.base.R.color.t_primary_night),
                )
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = currentPreset.displayText(),
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = colorResource(id = com.difft.android.base.R.color.t_secondary_night),
                    )
                )
                Icon(
                    painter = painterResource(id = R.drawable.call_btn_tabler_chevron_right),
                    contentDescription = null,
                    tint = colorResource(id = com.difft.android.base.R.color.t_secondary_night),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .width(menuWidth)
                .background(colorResource(id = com.difft.android.base.R.color.bg3_night)),
            offset = DpOffset(x = dropdownOffset, y = 0.dp)
        ) {
            VoicePreset.entries.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = preset.displayText(),
                            style = TextStyle(
                                fontSize = 16.sp,
                                lineHeight = 24.sp,
                                color = if (preset == currentPreset)
                                    colorResource(id = com.difft.android.base.R.color.primary)
                                else
                                    colorResource(id = com.difft.android.base.R.color.t_primary_night)
                            )
                        )
                    },
                    onClick = {
                        expanded = false
                        onPresetSelected(preset)
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    modifier = Modifier.height(46.dp)
                )
            }
        }
    }
}
