package com.difft.android.chat.util

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable







private class CustomDrawWrapper(
  private val wrapped: Drawable,
  private val drawFn: (wrapped: Drawable, canvas: Canvas) -> Unit
) : android.graphics.drawable.LayerDrawable(arrayOf(wrapped)) {
  override fun draw(canvas: Canvas) {
    drawFn(wrapped, canvas)
  }
}

fun Drawable.customizeOnDraw(customDrawFn: (wrapped: Drawable, canvas: Canvas) -> Unit): Drawable {
  return CustomDrawWrapper(this, customDrawFn)
}
