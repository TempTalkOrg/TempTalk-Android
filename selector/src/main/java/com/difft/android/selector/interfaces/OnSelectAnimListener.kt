package com.difft.android.selector.interfaces

import android.view.View

interface OnSelectAnimListener {
    /** @return anim duration */
    fun onSelectAnim(view: View): Long
}
