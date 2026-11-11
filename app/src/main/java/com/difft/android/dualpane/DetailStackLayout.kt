package com.difft.android.dualpane

import android.content.Context
import android.view.WindowInsets
import android.widget.FrameLayout










internal class DetailStackLayout(context: Context) : FrameLayout(context) {

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        for (i in 0 until childCount) getChildAt(i).dispatchApplyWindowInsets(WindowInsets(insets))
        return insets
    }
}
