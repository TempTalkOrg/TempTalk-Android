package com.difft.android.base.widget

import android.content.Context
import android.content.res.Configuration
import com.difft.android.base.ui.theme.tokens.ColorTokens
import kotlin.math.roundToInt






object SheetScrim {

    
    fun dimAmount(context: Context): Float =
        if (isDark(context)) ColorTokens.SheetScrimDimAmountDark else ColorTokens.ScrimDimAmount

    
    fun argb(context: Context): Int = (dimAmount(context) * ARGB_MAX).roundToInt() shl ALPHA_SHIFT

    private fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private const val ARGB_MAX = 255
    private const val ALPHA_SHIFT = 24
}
