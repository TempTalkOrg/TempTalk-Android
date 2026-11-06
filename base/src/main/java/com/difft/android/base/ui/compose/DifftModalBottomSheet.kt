package com.difft.android.base.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.utils.hideNavigationBar
import com.difft.android.base.utils.hideSystemBars

/**
 * Shared visual tokens for every bottom sheet in the app. The View-based
 * [com.difft.android.base.widget.BaseBottomSheetDialogFragment] mirrors these values through
 * `R.dimen.bottom_sheet_max_width`, `base_bg_bottom_sheet`, `base_bottom_sheet_container` and
 * `base_bg_bottom_sheet_drag_handle`; keep both sides in sync when changing any of them.
 */
object DifftBottomSheetDefaults {
    /** Material max width for modal bottom sheets. Same value as `R.dimen.bottom_sheet_max_width`. */
    val MaxWidth: Dp = 640.dp
    val CornerRadius: Dp = 16.dp
    val SheetShape: Shape = RoundedCornerShape(topStart = CornerRadius, topEnd = CornerRadius)
    val DragHandleWidth: Dp = 36.dp
    val DragHandleHeight: Dp = 5.dp
    val DragHandleTopPadding: Dp = 12.dp
    val DragHandleBottomPadding: Dp = 8.dp

    /**
     * Shared modal scrim (library: black 50 %). Read from [ColorTokens] rather than
     * `DifftTheme.colors.scrim`: this object is not composable and so cannot read a
     * CompositionLocal, and both themes carry the same value anyway.
     */
    val ScrimColor: Color = ColorTokens.Light.Scrim
}

/**
 * Project wrapper over Material3 [ModalBottomSheet] that owns the host-level look of a sheet:
 * width cap, corner shape, container color, drag handle, insets and (optionally) navigation-bar
 * hiding on the sheet's own dialog window.
 *
 * Deliberately exposes no `modifier` parameter. Material applies the caller modifier *outside*
 * its own `widthIn(max = sheetMaxWidth).fillMaxWidth()` chain, so a caller `fillMaxWidth()` /
 * `width()` silently defeats the 640dp cap (issue #1197). Width is controlled only via
 * [sheetMaxWidth].
 *
 * [containerColor] / [dragHandleColor] default to the [DifftTheme] tokens; every host mounting a
 * sheet provides that theme, so a caller only passes them to deviate from the library look.
 *
 * [hideSystemBars] hides every system bar on the sheet's dialog window, for a host window that is
 * itself fully immersive (call screen share, #1451); the dialog does not inherit that state, so
 * without it opening the sheet brings the status bar back. Turning it off while the sheet is open
 * shows the bars again (the navigation bar stays hidden under [hideNavigationBar]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifftModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    sheetMaxWidth: Dp = DifftBottomSheetDefaults.MaxWidth,
    shape: Shape = DifftBottomSheetDefaults.SheetShape,
    containerColor: Color = DifftTheme.colors.backgroundPopup,
    scrimColor: Color = DifftBottomSheetDefaults.ScrimColor,
    showDragHandle: Boolean = true,
    dragHandleColor: Color = DifftTheme.colors.textDisabled,
    hideNavigationBar: Boolean = false,
    hideSystemBars: Boolean = false,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetMaxWidth = sheetMaxWidth,
        shape = shape,
        containerColor = containerColor,
        scrimColor = scrimColor,
        dragHandle = if (showDragHandle) {
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
        content()
    }
}

/**
 * Drag handle with the same geometry as the View-side `base_bottom_sheet_container` handle
 * (36×5dp pill, 12dp above / 8dp below). Material's [BottomSheetDefaults.DragHandle] is not used
 * because it hard-codes 22dp vertical padding.
 */
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

/**
 * Hides the navigation bar on the dialog window that hosts the current composition
 * (a [ModalBottomSheet] creates its own window, which does not inherit the Activity's
 * navigation-bar state). No-op when the composition is not inside a dialog window.
 */
@Composable
fun HideDialogNavigationBarEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.hideNavigationBar()
        onDispose { }
    }
}

/**
 * Mirrors a fully immersive host on the dialog window of the current composition (#1451). Runs
 * as a [SideEffect] so the bars are hidden in the same pass as the sheet's first composition,
 * before its window draws; a no-op until [hidden] is first true, so default sheets are untouched.
 */
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
        }
    }
}

private class AppliedBarsState {
    var hidden = false
}
