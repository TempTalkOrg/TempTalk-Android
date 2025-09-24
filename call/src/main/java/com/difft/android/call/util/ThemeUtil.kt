package com.difft.android.call.util

import android.content.Context
import android.content.res.Configuration

object ThemeUtil {

    /**
     * Checks if the system is currently in dark theme mode
     * @param context Application context
     * @return true if system is in dark theme, false otherwise
     */
    fun isSystemInDarkTheme(context: Context?): Boolean {
        context ?: return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}