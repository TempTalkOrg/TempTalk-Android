package com.difft.android.call.util

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import java.lang.ref.WeakReference


object CallComposeUiUtil {

    /**
     * 将ComposeView添加到Activity的根布局中
     */
    fun addComposeViewToActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = android.widget.FrameLayout(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(composeView)
            // The host may never have called setContentView (e.g. a routing-only
            // startup Activity), so ComponentActivity never installed the ViewTree
            // owners on the decor. Compose resolves the recomposer lifecycle by
            // walking up from this content child, so install the owners here.
            (activity as? LifecycleOwner)?.let { setViewTreeLifecycleOwner(it) }
            (activity as? ViewModelStoreOwner)?.let { setViewTreeViewModelStoreOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
        }
        rootView.addView(frameLayout)

        // 保存引用以便后续移除
        composeView.tag = WeakReference(frameLayout)
    }

    /**
     * 从Activity中移除ComposeView
     */
    fun removeComposeViewFromActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = (composeView.tag as? WeakReference<*>)?.get() as? android.widget.FrameLayout
        frameLayout?.let {
            rootView.removeView(it)
        }
    }
}