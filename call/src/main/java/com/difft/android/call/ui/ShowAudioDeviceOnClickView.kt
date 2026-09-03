package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.BuildConfig
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.constraintlayout.compose.ConstraintLayout
import androidx.constraintlayout.compose.Dimension
import com.twilio.audioswitch.AudioDevice
import androidx.compose.ui.res.stringResource

/**
 * Dumb renderer for the audio-device picker: every status decision is already baked into
 * [AudioDeviceRow] by `toDeviceRows()`, so this file can never disagree with the horn icon about
 * which row is active.
 */
@Composable
fun ShowAudioDeviceOnClickView(
    modifier: Modifier = Modifier,
    rows: List<AudioDeviceRow>,
    expanded: Boolean,
    setExpanded:(Boolean) ->Unit,
    onClickItem: (AudioDevice) -> Unit
){
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    DropdownMenu(
        modifier = modifier
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .testTag("call_audio_picker")
            .clip(shape = RoundedCornerShape(12.dp))
            .background(DifftTheme.colors.backgroundPopup),
        expanded = expanded,
        onDismissRequest = {
            setExpanded(false)
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        },
    ) {
        rows.forEachIndexed { index, row ->
            // Stable node identity across list changes: a device connecting mid-call shifts
            // positions, and without a key the remembered painter of row N would be handed to a
            // different device. `kind` is unique per row (the library's device set holds at most
            // one entry per class) and, unlike the AudioDevice data class, never changes for a
            // given route.
            key(row.kind) {
                AudioDeviceRowItem(
                    index = index,
                    row = row,
                    onClick = { onClickItem(row.device) },
                )
            }
        }
    }
}

@Composable
private fun AudioDeviceRowItem(
    index: Int,
    row: AudioDeviceRow,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        modifier = Modifier
            .testTag("call_audio_picker_item_$index")
            .size(width = 247.dp, height = 30.dp)
            .background(
                DifftTheme.colors.backgroundPopup
            ),
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ){
                ConstraintLayout(
                    modifier = Modifier.fillMaxWidth()
                ){
                    val (deviceIcon, deviceName, deviceStatus) = createRefs()

                    Image(
                        painter = painterResource(id = row.kind.pickerIconRes()),
                        contentDescription = "audio device icon",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.constrainAs(deviceIcon){
                            start.linkTo(parent.start, margin = 8.dp)
                            centerVerticallyTo(parent)
                        }
                    )

                    // The status must never be the thing that gets ellipsized: it is the whole
                    // point of the row, and a Bluetooth productName is long enough to eat it.
                    // The device name shrinks instead. Always composed, even when empty, because
                    // deviceName's `end` constraint references this ref.
                    Text(
                        modifier = Modifier
                            .testTag("call_audio_picker_item_status_$index")
                            .constrainAs(deviceStatus){
                                end.linkTo(parent.end, margin = 8.dp)
                                centerVerticallyTo(parent)
                            },
                        text = row.status.labelRes()?.let { stringResource(it) }.orEmpty(),
                        fontSize = 13.sp,
                        color = when (row.status) {
                            AudioRouteRowStatus.FAILED -> DifftTheme.colors.textError
                            AudioRouteRowStatus.CONNECTING -> DifftTheme.colors.textSecondary
                            else -> DifftTheme.colors.textPrimary
                        },
                        maxLines = 1
                    )

                    Text(
                        modifier = Modifier.constrainAs(deviceName){
                            start.linkTo(deviceIcon.end, 6.dp)
                            end.linkTo(deviceStatus.start, 6.dp)
                            centerVerticallyTo(parent)
                            width = Dimension.fillToConstraints
                        },
                        text = row.device.name,
                        fontSize = 17.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

        },
        onClick = onClick,
    )
}