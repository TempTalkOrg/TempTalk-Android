package com.difft.android.call.util

import android.content.Context
import android.content.res.Configuration

object ThemeUtil {

    




    fun isSystemInDarkTheme(context: Context?): Boolean {
        context ?: return false
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }
}