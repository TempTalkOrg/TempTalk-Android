package com.difft.android.selector.interfaces

import java.util.ArrayList

interface OnResultCallbackListener<T> {
    fun onResult(result: ArrayList<T>)

    fun onCancel()
}
