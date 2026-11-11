package com.difft.android.call.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallViewModel
import com.difft.android.call.manager.AudioDeviceKind
import com.difft.android.call.manager.logName
import com.difft.android.call.ui.actionbar.ActionButtonStyle
import com.difft.android.call.ui.actionbar.CallActionButton


private const val HORN_PENDING_ALPHA = 0.4f










@Composable
fun AudioRouteControl(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    controlSize: Dp,
    modifier: Modifier = Modifier,
) {
    val route by viewModel.audioRoute.collectAsState()
    val rows = route.toDeviceRows()
    
    val panel = shouldShowAudioDevicePanel(rows)
    val presentation = route.hornPresentation(isOneVOneCall = isOneVOneCall, isToggle = !panel)
    val hornPainter = painterResource(id = presentation.kind.hornIconRes())
    
    
    val selected = presentation.kind == AudioDeviceKind.SPEAKERPHONE && !presentation.pending

    var expanded by remember { mutableStateOf(false) }

    
    
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isEmpty() && expanded) {
            L.i { "[call] audioRoute panel autoDismiss reason=noDevices" }
            expanded = false
        }
    }

    Box(modifier = modifier.size(controlSize)) {
        CallActionButton(
            painter = hornPainter,
            contentDescription = "Horn",
            size = controlSize,
            style = if (selected) ActionButtonStyle.SELECTED else ActionButtonStyle.NORMAL,
            
            
            iconAlpha = if (presentation.pending) HORN_PENDING_ALPHA else 1f,
            testTag = "call_btn_horn",
            onClick = {
                
                val tapState = route.state.logName
                L.i {
                    "[call] audioRoute hornTap count=${rows.size} panel=$panel " +
                        "kinds=${rows.joinToString(",") { it.kind.name }} " +
                        "shown=${presentation.kind} pending=${presentation.pending} " +
                        "state=$tapState"
                }
                when {
                    
                    rows.isEmpty() -> Unit
                    panel -> expanded = !expanded
                    else -> viewModel.audioDeviceManager.switchToNext()
                }
            },
        )

        ShowAudioDeviceOnClickView(
            rows = rows,
            expanded = expanded,
            setExpanded = { value -> expanded = value },
            onClickItem = { device ->
                viewModel.audioDeviceManager.select(device)
                expanded = false
            }
        )
    }
}
