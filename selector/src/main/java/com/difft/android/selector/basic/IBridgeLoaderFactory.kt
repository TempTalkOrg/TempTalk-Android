package com.difft.android.selector.basic

import com.difft.android.selector.loader.IBridgeMediaLoader

interface IBridgeLoaderFactory {
    /** CreateLoader */
    fun onCreateLoader(): IBridgeMediaLoader
}
