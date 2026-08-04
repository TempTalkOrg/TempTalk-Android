package com.difft.android.imageeditor.core

import androidx.annotation.ColorInt

/**
 * A renderer that can have its color changed.
 *
 * For example, Lines and Text can change color.
 */
interface ColorableRenderer : Renderer {

    @ColorInt
    fun getColor(): Int

    fun setColor(@ColorInt color: Int)
}
