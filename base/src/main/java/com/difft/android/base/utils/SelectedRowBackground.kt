package com.difft.android.base.utils

import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R

/**
 * Two-pane list rows: the row whose conversation or contact is open in the detail pane sits on
 * bg3 (the conversation list's selected-row colour); every other row keeps a transparent
 * background, so the list's own background shows through.
 */
fun View.setSearchRowSelected(selected: Boolean) {
    if (selected) setBackgroundColor(ContextCompat.getColor(context, R.color.bg3)) else setBackgroundColor(Color.TRANSPARENT)
}
