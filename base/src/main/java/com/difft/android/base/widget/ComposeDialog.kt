package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.difft.android.base.ui.theme.DifftTheme

interface ComposeDialog {
    fun dismiss()

    



    val isShowing: Boolean get() = true
}











@Composable
internal fun DialogTheme(content: @Composable () -> Unit) {
    DifftTheme(
        darkTheme = isSystemInDarkTheme(),
        applyWindowBackground = false,
        content = content
    )
}


fun Context.getSafeActivity(): Activity? {
    fun isValidActivity(activity: Activity?) = activity != null && !activity.isFinishing && !activity.isDestroyed

    return when (this) {
        is Activity -> {
            if (isValidActivity(this)) this else null
        }

        is ContextWrapper -> {
            var context = this.baseContext
            while (context is ContextWrapper) {
                if (context is Activity) {
                    return if (isValidActivity(context)) context else null
                }
                context = context.baseContext
            }
            null
        }

        else -> null
    }
}
