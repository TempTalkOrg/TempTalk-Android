package com.difft.android.selector.interfaces

import android.content.Context

import com.difft.android.selector.config.InjectResourceSource

interface OnInjectLayoutResourceListener {
    /**
     * Inject a custom layout resource id. The overloaded layout must keep the same view IDs
     * as the built-in layout it replaces.
     *
     * @param resourceSource one of [InjectResourceSource]
     */
    fun getLayoutResourceId(context: Context?, resourceSource: Int): Int
}
