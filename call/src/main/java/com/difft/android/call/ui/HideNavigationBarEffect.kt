package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.difft.android.call.util.ViewUtil

/**
 * Hides the navigation bar on the dialog/popup window that hosts a
 * [ModalBottomSheet][androidx.compose.material3.ModalBottomSheet].
 *
 * `ModalBottomSheet` creates its own window which does NOT inherit the
 * Activity's navigation-bar-hidden state. Place this call as the first
 * composable inside the sheet content to keep the bar hidden.
 *
 * When the sheet is dismissed, the Activity-level
 * [OnSystemUiVisibilityChangeListener][android.view.View.OnSystemUiVisibilityChangeListener]
 * (registered in [configureWindow][com.difft.android.call.configureWindow])
 * re-hides the navigation bar on the Activity window automatically.
 */
@Composable
internal fun HideNavigationBarEffect() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        (view.parent as? DialogWindowProvider)?.window?.let(ViewUtil::hideNavigationBar)
        onDispose { }
    }
}
