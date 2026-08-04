package com.difft.android.selector.interfaces

import java.util.ArrayList

open class OnQueryDataResultListener<T> {
    open fun onComplete(result: ArrayList<T>, isHasMore: Boolean) {
    }
}
