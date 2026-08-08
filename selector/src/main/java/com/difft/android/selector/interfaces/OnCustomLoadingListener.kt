package com.difft.android.selector.interfaces

import android.app.Dialog
import android.content.Context

interface OnCustomLoadingListener {
    fun create(context: Context): Dialog
}
