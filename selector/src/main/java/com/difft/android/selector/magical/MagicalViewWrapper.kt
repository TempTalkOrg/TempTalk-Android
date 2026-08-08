package com.difft.android.selector.magical

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

class MagicalViewWrapper(view: View) {
    private val params: ViewGroup.MarginLayoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
    private val viewWrapper: View = view

    init {
        if (params is LinearLayout.LayoutParams) {
            params.gravity = Gravity.START
        }
    }

    fun setWidth(width: Float) {
        params.width = Math.round(width)
        viewWrapper.layoutParams = params
    }

    fun setHeight(height: Float) {
        params.height = Math.round(height)
        viewWrapper.layoutParams = params
    }

    fun setMarginTop(m: Int) {
        params.topMargin = m
        viewWrapper.layoutParams = params
    }

    fun setMarginLeft(mr: Int) {
        params.leftMargin = mr
        viewWrapper.layoutParams = params
    }
}
