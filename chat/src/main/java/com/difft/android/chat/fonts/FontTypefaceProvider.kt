package com.difft.android.chat.fonts

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import com.difft.android.imageeditor.core.Renderer
import com.difft.android.imageeditor.core.RendererContext

/**
 * TypefaceProvider that provides system bold typeface for image editor text rendering.
 */
class FontTypefaceProvider : RendererContext.TypefaceProvider {

  override fun getSelectedTypeface(context: Context, renderer: Renderer, invalidate: RendererContext.Invalidate): Typeface {
    return if (Build.VERSION.SDK_INT < 26) {
      Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    } else {
      Typeface.Builder("")
        .setFallback("sans-serif")
        .setWeight(900)
        .build()
    }
  }
}
