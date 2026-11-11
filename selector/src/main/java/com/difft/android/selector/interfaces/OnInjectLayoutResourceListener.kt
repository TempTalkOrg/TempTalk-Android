package com.difft.android.selector.interfaces

import android.content.Context

import com.difft.android.selector.config.InjectResourceSource

interface OnInjectLayoutResourceListener {
    





    fun getLayoutResourceId(context: Context?, resourceSource: Int): Int
}
