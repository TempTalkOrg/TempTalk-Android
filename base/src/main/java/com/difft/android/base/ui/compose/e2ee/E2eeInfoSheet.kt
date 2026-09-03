package com.difft.android.base.ui.compose.e2ee

import android.app.Activity
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ComposeActivityMount
import kotlinx.coroutines.launch

/**
 * Mounts the E2EE explainer sheet on [activity]'s root view. For View/Fragment-based
 * hosts (chat list, chat page) that have no live Compose tree to embed into — use
 * [E2eeInfoSheetDialog] instead when already inside a Compose composition (:call).
 *
 * @param darkTheme explicit theme — chat/list callers pass the App's current theme
 *   (`resources.configuration` night-mode check or existing DifftTheme darkTheme source);
 *   this function does NOT infer it, so getting this wrong silently defaults every popup
 *   to system theme instead of app theme.
 * @param learnMoreUrl caller-supplied (from UrlManager.e2eeLearnMoreUrl) — :base has no
 *   project() dependency on :network and cannot read UrlManager itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
object E2eeInfoSheet {
    fun show(activity: Activity, darkTheme: Boolean, learnMoreUrl: String) {
        lateinit var composeView: ComposeView
        composeView = ComposeView(activity).apply {
            setContent {
                DifftTheme(darkTheme = darkTheme, applyWindowBackground = false) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val scope = rememberCoroutineScope()
                    val dismiss: () -> Unit = {
                        scope.launch {
                            sheetState.hide()
                            ComposeActivityMount.unmount(activity, composeView)
                        }
                    }
                    ModalBottomSheet(
                        onDismissRequest = dismiss,
                        sheetState = sheetState,
                        containerColor = DifftTheme.colors.backgroundBottomSheet,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        dragHandle = {
                            BottomSheetDefaults.DragHandle(
                                width = 35.dp,
                                height = 5.dp,
                                color = DifftTheme.colors.textDisabled,
                            )
                        },
                    ) {
                        E2eeInfoSheetContent(learnMoreUrl = learnMoreUrl, onDismissRequest = dismiss)
                    }
                }
            }
        }

        // This ComposeView is mounted directly onto the Activity's root content view (not a
        // separate dialog window), so it does not own the host Activity's window —
        // DifftTheme(applyWindowBackground = false) above ensures its SideEffect never touches
        // activity.window at all, so there is nothing to snapshot or restore here.
        ComposeActivityMount.mount(activity, composeView)
    }
}

/**
 * Inline entry for hosts already inside a live Compose tree (CallContent.kt's subtree,
 * which already wraps DifftTheme(darkTheme = true) — this function must NOT wrap its own
 * DifftTheme, or it would double-apply/shadow the ambient one).
 * Follows the existing :call state-driven sheet idiom (ShowItemsBottomView.kt):
 * caller owns [showSheet] as a boolean state; this composable owns show/hide animation
 * timing via its internal sheetState.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun E2eeInfoSheetDialog(
    showSheet: Boolean,
    learnMoreUrl: String,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismissRequest()
        }
    }
    LaunchedEffect(showSheet) {
        if (showSheet) sheetState.show() else sheetState.hide()
    }
    if (showSheet || sheetState.isVisible) {
        ModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = sheetState,
            containerColor = DifftTheme.colors.backgroundBottomSheet,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            dragHandle = {
                BottomSheetDefaults.DragHandle(
                    width = 35.dp,
                    height = 5.dp,
                    color = DifftTheme.colors.textDisabled,
                )
            },
        ) {
            E2eeInfoSheetContent(learnMoreUrl = learnMoreUrl, onDismissRequest = dismiss)
        }
    }
}
