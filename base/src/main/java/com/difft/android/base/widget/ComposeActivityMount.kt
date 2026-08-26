package com.difft.android.base.widget

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Mounts/unmounts a [ComposeView] directly onto an Activity's root content view, outside of
 * any Fragment/View hierarchy.
 *
 * The host Activity may never have called `setContentView` (e.g. a routing-only startup
 * Activity), so ComponentActivity never installed the ViewTree owners on the decor. Compose
 * resolves the recomposer lifecycle by walking up from this content child, so the owners are
 * installed here explicitly.
 */
internal object ComposeActivityMount {
    fun mount(activity: Activity, composeView: ComposeView) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val frameLayout = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(composeView)
            (activity as? LifecycleOwner)?.let { setViewTreeLifecycleOwner(it) }
            (activity as? ViewModelStoreOwner)?.let { setViewTreeViewModelStoreOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
        }
        rootView.addView(frameLayout)
        composeView.tag = frameLayout
    }

    fun unmount(activity: Activity, composeView: ComposeView) {
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        (composeView.tag as? FrameLayout)?.let { rootView.removeView(it) }
    }
}
