package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.difft.android.base.R
import com.difft.android.base.widget.SheetScrim
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftRadius
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.hideNavigationBar
import com.difft.android.base.utils.hideSystemBars








object DifftBottomSheetDefaults {
    
    val MaxWidth: Dp = 640.dp

    



    val Inset: Dp = 8.dp
    val CornerRadius: Dp = DifftRadius.floating
    val SheetShape: Shape = RoundedCornerShape(CornerRadius)
    val DragHandleWidth: Dp = 36.dp
    val DragHandleHeight: Dp = 5.dp
    val DragHandleTopPadding: Dp = 8.dp
    val DragHandleBottomPadding: Dp = 8.dp

    




    val ScrimColor: Color = ColorTokens.Light.Scrim
}























@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifftModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = DifftBottomSheetDefaults.MaxWidth,
    shape: Shape = DifftBottomSheetDefaults.SheetShape,
    containerColor: Color = DifftTheme.colors.backgroundPopup,
    scrimColor: Color = Color(SheetScrim.argb(LocalContext.current)),
    showDragHandle: Boolean = true,
    dragHandleColor: Color = DifftTheme.colors.textDisabled,
    hideNavigationBar: Boolean = false,
    hideSystemBars: Boolean = false,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    val inset = containerColor.alpha > 0f
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = if (inset) RectangleShape else shape,
        containerColor = if (inset) Color.Transparent else containerColor,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = if (showDragHandle && !inset) {
            { DifftDragHandle(color = dragHandleColor) }
        } else {
            null
        },
        contentWindowInsets = contentWindowInsets,
    ) {
        if (hideNavigationBar) {
            HideDialogNavigationBarEffect()
        }
        DialogSystemBarsEffect(hidden = hideSystemBars, keepNavigationBarHidden = hideNavigationBar)
        DialogStatusBarSettingEffect(enabled = !hideSystemBars)
        if (inset) {
            Column(
                modifier = Modifier
                    .padding(start = DifftBottomSheetDefaults.Inset, end = DifftBottomSheetDefaults.Inset, bottom = DifftBottomSheetDefaults.Inset)
                    .floatingSurface(shape, fill = containerColor),
            ) {
                if (showDragHandle) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { DifftDragHandle(color = dragHandleColor) }
                }
                content()
            }
        } else {
            content()
        }
    }
}






@Composable
fun DifftDragHandle(color: Color = DifftTheme.colors.textDisabled) {
    val description = stringResource(R.string.base_bottom_sheet_drag_handle)
    Box(
        modifier = Modifier
            .padding(
                top = DifftBottomSheetDefaults.DragHandleTopPadding,
                bottom = DifftBottomSheetDefaults.DragHandleBottomPadding,
            )
            .size(DifftBottomSheetDefaults.DragHandleWidth, DifftBottomSheetDefaults.DragHandleHeight)
            .background(color = color, shape = CircleShape)
            .semantics { contentDescription = description },
    )
}






@Composable
fun HideDialogNavigationBarEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.hideNavigationBar()
        onDispose { }
    }
}






@Composable
private fun DialogSystemBarsEffect(hidden: Boolean, keepNavigationBarHidden: Boolean) {
    val view = LocalView.current
    val applied = remember { AppliedBarsState() }
    SideEffect {
        if (hidden == applied.hidden) return@SideEffect
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        applied.hidden = hidden
        L.i { "[BottomSheet] dialog system bars hidden=$hidden" }
        if (hidden) {
            window.hideSystemBars()
        } else {
            WindowCompat.getInsetsController(window, window.decorView)
                .show(WindowInsetsCompat.Type.systemBars())
            if (keepNavigationBarHidden) window.hideNavigationBar()
            
            StatusBarSetting.apply(view.context, window)
        }
    }
}

private class AppliedBarsState {
    var hidden = false
}
