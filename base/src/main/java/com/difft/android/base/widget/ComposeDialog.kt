package com.difft.android.base.widget

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.difft.android.base.ui.theme.DifftTheme

/**
 * Dialog接口，提供统一的dismiss方法
 */
interface ComposeDialog {
    fun dismiss()
}

/**
 * Theme host for the dialogs [ComposeDialogManager] and [WaitDialogSlot] mount. They live in a
 * bare `ComposeView` added onto the Activity's root view, and `LocalDifftExtendedColors` defaults
 * to the light palette, so without this every `DifftTheme.colors` read inside a dialog would render
 * light colours in dark mode.
 *
 * `isSystemInDarkTheme()` reads the host Activity's configuration — the same source the XML night
 * qualifier resolved against, AppCompat's `setDefaultNightMode` override included. The overlay does
 * not own the host window, hence `applyWindowBackground = false`.
 */
@Composable
internal fun DialogTheme(content: @Composable () -> Unit) {
    DifftTheme(
        darkTheme = isSystemInDarkTheme(),
        applyWindowBackground = false,
        content = content
    )
}

/**
 * Context扩展函数：安全获取Activity
 * 如果无法获取到有效的Activity，返回null
 */
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
