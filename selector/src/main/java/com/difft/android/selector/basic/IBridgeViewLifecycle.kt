package com.difft.android.selector.basic

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

interface IBridgeViewLifecycle {
    
    fun onViewCreated(fragment: Fragment?, view: View?, savedInstanceState: Bundle?)

    
    fun onDestroy(fragment: Fragment?)
}
