package com.difft.android.selector.interfaces

interface OnQueryAllAlbumListener<T> {
    fun onComplete(result: List<@JvmSuppressWildcards T>)
}
