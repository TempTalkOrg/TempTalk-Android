package com.difft.android.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.R
import com.github.TempTalkOrg.audio_pipeline.AudioModule

@Composable
fun DeNoiseModeSelector(
    currentMode: AudioModule,
    isParticipantSharedScreen: Boolean,
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
            .then(if (isParticipantSharedScreen) Modifier.width(343.dp) else Modifier.fillMaxWidth())
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
                text = ResUtils.getString(R.string.call_denoise_mode_label),
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
                    text = modeLabel,
                    style = TextStyle(
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight(400),
                        color = colorResource(id = com.difft.android.base.R.color.gray_50),
                    )
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = colorResource(id = com.difft.android.base.R.color.gray_50),
                    modifier = Modifier
                        .width(20.dp)
                        .height(20.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colorResource(id = com.difft.android.base.R.color.gray_600))
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = ResUtils.getString(R.string.call_denoise_mode_standard),
                        color = if (currentMode == AudioModule.RNNOISE)
                            colorResource(id = com.difft.android.base.R.color.primary)
                        else colorResource(id = com.difft.android.base.R.color.t_primary_night)
                    )
                },
                onClick = {
                    expanded = false
                    onModeSelected(AudioModule.RNNOISE)
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = ResUtils.getString(R.string.call_denoise_mode_enhanced),
                        color = if (currentMode == AudioModule.DEEP_FILTER_NET)
                            colorResource(id = com.difft.android.base.R.color.primary)
                        else colorResource(id = com.difft.android.base.R.color.t_primary_night)
                    )
                },
                onClick = {
                    expanded = false
                    onModeSelected(AudioModule.DEEP_FILTER_NET)
                }
            )
        }
    }
}
