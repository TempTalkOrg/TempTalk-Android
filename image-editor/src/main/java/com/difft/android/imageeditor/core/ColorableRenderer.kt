package com.difft.android.imageeditor.core

import androidx.annotation.ColorInt






interface ColorableRenderer : Renderer {

    @ColorInt
    fun getColor(): Int

    fun setColor(@ColorInt color: Int)
}
