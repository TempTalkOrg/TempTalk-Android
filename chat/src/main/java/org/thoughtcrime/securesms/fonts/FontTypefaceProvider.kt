package org.thoughtcrime.securesms.fonts

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import org.signal.imageeditor.core.Renderer
import org.signal.imageeditor.core.RendererContext

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
