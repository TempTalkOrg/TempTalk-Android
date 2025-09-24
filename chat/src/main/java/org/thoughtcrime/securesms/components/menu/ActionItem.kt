package org.thoughtcrime.securesms.components.menu

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes

/**
 * Represents an action to be rendered via [SignalContextMenu] or [SignalBottomActionBar]
 */
data class ActionItem @JvmOverloads constructor(
    @DrawableRes val iconRes: Int,
    val title: CharSequence,
    @ColorRes val tintRes: Int = com.difft.android.base.R.color.icon,
    val action: Runnable? = null
)
