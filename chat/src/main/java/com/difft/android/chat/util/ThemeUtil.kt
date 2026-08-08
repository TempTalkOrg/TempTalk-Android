package com.difft.android.chat.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt

object ThemeUtil {

    @JvmStatic
    fun isDarkNotificationTheme(context: Context): Boolean {
        return (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    @JvmStatic
    fun getThemedBoolean(context: Context, @AttrRes attr: Int): Boolean {
        val typedValue = TypedValue()
        val theme = context.theme

        if (theme.resolveAttribute(attr, typedValue, true)) {
            return typedValue.data != 0
        }

        return false
    }

    @JvmStatic
    @ColorInt
    fun getThemedColor(context: Context, @AttrRes attr: Int): Int {
        val typedValue = TypedValue()
        val theme = context.theme

        if (theme.resolveAttribute(attr, typedValue, true)) {
            return typedValue.data
        }
        return Color.RED
    }
}
