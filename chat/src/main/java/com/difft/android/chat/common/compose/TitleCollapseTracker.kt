package com.difft.android.chat.common.compose

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Drives [CollapsingTitleBar]'s `collapsed` flag from the identity header's avatar position.
 *
 * [IdentityHeader] reports the avatar's bottom edge in window coordinates via
 * `onAvatarBottomChanged`; Compose re-dispatches that callback whenever an ancestor scroll view
 * moves the header, so no separate scroll listener is needed. The bar is collapsed once that edge
 * is at or above the title bar's bottom edge. Negative values are legitimate (avatar scrolled above
 * the window top) and stay collapsed; until the first report the bar stays expanded.
 */
class TitleCollapseTracker(private val titleBar: View) {
    var collapsed by mutableStateOf(false)
        private set

    // Reused across reports: this runs on every positioned callback while the page scrolls.
    private val location = IntArray(2)

    fun onAvatarBottomChanged(bottom: Float) {
        titleBar.getLocationInWindow(location)
        collapsed = bottom <= location[1] + titleBar.height
    }
}
