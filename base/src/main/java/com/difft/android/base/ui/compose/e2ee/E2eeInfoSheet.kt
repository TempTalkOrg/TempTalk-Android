package com.difft.android.base.ui.compose.e2ee

import android.app.Activity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ComposeView
import com.difft.android.base.ui.compose.DifftModalBottomSheet
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.widget.ComposeActivityMount
import kotlinx.coroutines.launch













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
                    DifftModalBottomSheet(
                        onDismissRequest = dismiss,
                        sheetState = sheetState,
                    ) {
                        E2eeInfoSheetContent(learnMoreUrl = learnMoreUrl, onDismissRequest = dismiss)
                    }
                }
            }
        }

        
        
        
        
        ComposeActivityMount.mount(activity, composeView)
    }
}









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
        DifftModalBottomSheet(
            onDismissRequest = dismiss,
            sheetState = sheetState,
        ) {
            E2eeInfoSheetContent(learnMoreUrl = learnMoreUrl, onDismissRequest = dismiss)
        }
    }
}
