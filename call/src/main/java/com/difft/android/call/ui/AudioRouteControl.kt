package com.difft.android.call.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallViewModel
import com.difft.android.call.manager.logName

/** Opacity, not a color — no DifftTheme token applies. Tunable without a redesign. */
private const val HORN_PENDING_ALPHA = 0.4f

/**
 * Horn button plus the device picker it opens.
 *
 * The route snapshot is collected HERE rather than in the bottom control bar: the snapshot now
 * changes on every library device callback (the scanner refreshes on the main thread), and the
 * sibling controls must not be invalidated — and therefore re-measured — for that. Compose scopes
 * recomposition to the function that reads the state, so the blast radius is this horn `Image`
 * plus the `DropdownMenu`.
 */
@Composable
fun AudioRouteControl(
    viewModel: LCallViewModel,
    isOneVOneCall: Boolean,
    controlSize: Dp,
    modifier: Modifier = Modifier,
) {
    val route by viewModel.audioRoute.collectAsState()
    val rows = route.toDeviceRows()
    // Hoisted out of the click lambda: the presentation and the tap must not decide this twice.
    val panel = shouldShowAudioDevicePanel(rows)
    val presentation = route.hornPresentation(isOneVOneCall = isOneVOneCall, isToggle = !panel)
    val hornPainter = painterResource(id = presentation.kind.hornIconRes())

    var expanded by remember { mutableStateOf(false) }

    // A side effect, not composition-phase work: if the last device disappears while the panel is
    // open (headset yanked, AudioSwitch invalidated) an open menu would render zero rows.
    LaunchedEffect(rows.isEmpty()) {
        if (rows.isEmpty() && expanded) {
            L.i { "[call] audioRoute panel autoDismiss reason=noDevices" }
            expanded = false
        }
    }

    Surface(
        modifier = modifier.size(controlSize),
        color = Color.Transparent
    ) {
        Image(
            painter = hornPainter,
            contentDescription = "Horn",
            contentScale = ContentScale.Fit,
            // Dimmed = "switching to here", solid = "audio is here". Must stay on the Image: a draw
            // parameter only, so no layout pass and no dimmed click target.
            alpha = if (presentation.pending) HORN_PENDING_ALPHA else 1f,
            modifier = Modifier
                .testTag("call_btn_horn")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    // Capture now: the log lambda may evaluate after switchToNext mutates the state.
                    val tapState = route.state.logName
                    L.i {
                        "[call] audioRoute hornTap count=${rows.size} panel=$panel " +
                            "kinds=${rows.joinToString(",") { it.kind.name }} " +
                            "shown=${presentation.kind} pending=${presentation.pending} " +
                            "state=$tapState"
                    }
                    when {
                        // Nothing enumerated yet; the tap is logged above rather than dropped.
                        rows.isEmpty() -> Unit
                        panel -> expanded = !expanded
                        else -> viewModel.audioDeviceManager.switchToNext()
                    }
                }
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
