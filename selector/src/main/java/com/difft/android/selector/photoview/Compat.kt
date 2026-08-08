package com.difft.android.selector.photoview

import android.view.View

internal object Compat {

    fun postOnAnimation(view: View, runnable: Runnable) {
        postOnAnimationJellyBean(view, runnable)
    }

    private fun postOnAnimationJellyBean(view: View, runnable: Runnable) {
        view.postOnAnimation(runnable)
    }
}
