package com.difft.android.selector.config

import android.content.Context

object InjectResourceSource {
    const val DEFAULT_LAYOUT_RESOURCE = 0

    const val MAIN_SELECTOR_LAYOUT_RESOURCE = 1

    const val PREVIEW_LAYOUT_RESOURCE = 2

    const val MAIN_ITEM_IMAGE_LAYOUT_RESOURCE = 3

    const val MAIN_ITEM_VIDEO_LAYOUT_RESOURCE = 4

    const val ALBUM_ITEM_LAYOUT_RESOURCE = 6

    const val PREVIEW_ITEM_IMAGE_LAYOUT_RESOURCE = 7

    const val PREVIEW_ITEM_VIDEO_LAYOUT_RESOURCE = 8

    const val PREVIEW_GALLERY_ITEM_LAYOUT_RESOURCE = 9

    @JvmStatic
    fun getLayoutResource(context: Context?, resourceSource: Int, selectorConfig: SelectorConfig?): Int {
        val listener = selectorConfig?.onLayoutResourceListener
        if (listener != null) {
            return listener.getLayoutResourceId(context, resourceSource)
        }
        return DEFAULT_LAYOUT_RESOURCE
    }
}
