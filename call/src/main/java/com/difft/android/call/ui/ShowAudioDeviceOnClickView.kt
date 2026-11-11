package com.difft.android.call.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.compose.menu.DifftDropdownMenu
import com.difft.android.base.ui.compose.menu.DifftDropdownMenuItem
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.BuildConfig
import com.twilio.audioswitch.AudioDevice
import androidx.compose.ui.res.stringResource


private val AUDIO_PICKER_WIDTH = 247.dp






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

    DifftDropdownMenu(
        modifier = modifier
            .semantics { testTagsAsResourceId = BuildConfig.DEBUG }
            .testTag("call_audio_picker")
            .width(AUDIO_PICKER_WIDTH),
        expanded = expanded,
        onDismissRequest = {
            setExpanded(false)
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        },
    ) {
        rows.forEachIndexed { index, row ->
            
            
            
            
            
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
    DifftDropdownMenuItem(
        modifier = Modifier.testTag("call_audio_picker_item_$index"),
        text = row.device.name,
        iconRes = row.kind.pickerIconRes(),
        onClick = onClick,
        trailing = {
            
            
            
            
            Text(
                modifier = Modifier.testTag("call_audio_picker_item_status_$index"),
                text = row.status.labelRes()?.let { stringResource(it) }.orEmpty(),
                style = DifftTheme.typography.footnote,
                color = when (row.status) {
                    AudioRouteRowStatus.FAILED -> DifftTheme.colors.textError
                    AudioRouteRowStatus.CONNECTING -> DifftTheme.colors.textSecondary
                    else -> DifftTheme.colors.textPrimary
                },
                maxLines = 1
            )
        },
    )
}