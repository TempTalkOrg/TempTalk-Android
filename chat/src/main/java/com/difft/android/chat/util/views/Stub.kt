package com.difft.android.chat.util.views

import android.view.View
import android.view.ViewStub

class Stub<T : View>(viewStub: ViewStub) {

    private var viewStub: ViewStub? = viewStub
    private var view: T? = null

    fun get(): T {
        if (view == null) {
            @Suppress("UNCHECKED_CAST")
            view = viewStub!!.inflate() as T
            viewStub = null
        }
        return view!!
    }

    fun resolved(): Boolean {
        return view != null
    }

    fun setVisibility(visibility: Int) {
        if (resolved() || visibility == View.VISIBLE) {
            get().visibility = visibility
        }
    }

    fun getVisibility(): Int {
        return if (resolved()) {
            get().visibility
        } else {
            View.GONE
        }
    }

    fun isVisible(): Boolean {
        return getVisibility() == View.VISIBLE
    }
}
