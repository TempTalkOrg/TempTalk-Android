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
import com.difft.android.base.log.lumberjack.L

/**
 * Mounts/unmounts a [ComposeView] directly onto an Activity's root content view, outside of
 * any Fragment/View hierarchy.
 *
 * The host may never have called `setContentView`, so the ViewTree owners are installed on
 * the wrapper here. A host that is not a [LifecycleOwner] is skipped: it has no owners, and
 * Compose would crash on first attach.
 */
internal object ComposeActivityMount {
    fun mount(activity: Activity, composeView: ComposeView) {
        if (activity !is LifecycleOwner) {
            L.w { "[ComposeActivityMount] mount skipped, host is not a LifecycleOwner: ${activity::class.java.name}" }
            return
        }
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)
        val frameLayout = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(composeView)
            setViewTreeLifecycleOwner(activity)
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
