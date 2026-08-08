package com.difft.android.selector.basic

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

interface IBridgeViewLifecycle {
    /** onViewCreated */
    fun onViewCreated(fragment: Fragment?, view: View?, savedInstanceState: Bundle?)

    /** onDestroy */
    fun onDestroy(fragment: Fragment?)
}
