package com.difft.android.base.utils

import android.graphics.Color
import android.view.View
import androidx.core.content.ContextCompat
import com.difft.android.base.R






fun View.setSearchRowSelected(selected: Boolean) {
    if (selected) setBackgroundColor(ContextCompat.getColor(context, R.color.bg3)) else setBackgroundColor(Color.TRANSPARENT)
}
