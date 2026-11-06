package com.difft.android.dualpane

import android.content.Context
import android.view.WindowInsets
import android.widget.FrameLayout

/**
 * Page container of one tab's detail stack. A plain ViewGroup draws a removed child that is still
 * animating out (a popped page) after the others, so a one-pane pop slides the leaving page over
 * the one coming back, and a push slides the new, last-added page over the covered one; a
 * FragmentContainerView would draw the leaving page underneath instead.
 *
 * Like FragmentContainerView, every page receives the window insets: a covered page consuming
 * them (a conversation handling the IME) must not starve the page above it.
 */
internal class DetailStackLayout(context: Context) : FrameLayout(context) {

    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        for (i in 0 until childCount) getChildAt(i).dispatchApplyWindowInsets(WindowInsets(insets))
        return insets
    }
}
