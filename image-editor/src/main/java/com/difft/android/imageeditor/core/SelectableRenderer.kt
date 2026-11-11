package com.difft.android.imageeditor.core

import android.graphics.RectF




interface SelectableRenderer : Renderer {
  fun onSelected(selected: Boolean)

  


  fun getSelectionBounds(bounds: RectF)
}
