package com.difft.android.base.utils

import android.content.Context
import android.provider.Settings

/**
 * Android has no `prefers-reduced-motion`; ANIMATOR_DURATION_SCALE == 0 (Settings >
 * Accessibility > Remove animations, or a dev-options toggle) is the closest equivalent.
 * Consumed by the call-screen's calling-banner alternation to fall back to a static
 * "already encrypted" label instead of animating.
 */
fun isAnimationsDisabled(context: Context): Boolean {
    val scale = Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    )
    return scale == 0f
}
