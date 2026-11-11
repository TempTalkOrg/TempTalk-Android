package com.difft.android.chat.common.compose

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue










class TitleCollapseTracker(private val titleBar: View) {
    var collapsed by mutableStateOf(false)
        private set

    
    private val location = IntArray(2)

    fun onAvatarBottomChanged(bottom: Float) {
        titleBar.getLocationInWindow(location)
        collapsed = bottom <= location[1] + titleBar.height
    }
}
