package com.difft.android.base.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import com.difft.android.base.utils.StatusBarSetting







@Composable
fun DialogStatusBarSettingEffect(enabled: Boolean = true) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (enabled && window != null) StatusBarSetting.attach(context, window)
        onDispose { window?.let(StatusBarSetting::detach) }
    }
}
