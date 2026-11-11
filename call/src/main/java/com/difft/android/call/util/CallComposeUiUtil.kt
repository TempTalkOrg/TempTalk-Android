package com.difft.android.call.util

import android.app.Activity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.difft.android.base.log.lumberjack.L
import java.lang.ref.WeakReference


object CallComposeUiUtil {

    




    fun addComposeViewToActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        if (activity !is LifecycleOwner) {
            L.w { "[Call] addComposeViewToActivity skipped, host is not a LifecycleOwner: ${activity::class.java.name}" }
            return
        }
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = android.widget.FrameLayout(activity).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            addView(composeView)
            
            setViewTreeLifecycleOwner(activity)
            (activity as? ViewModelStoreOwner)?.let { setViewTreeViewModelStoreOwner(it) }
            (activity as? SavedStateRegistryOwner)?.let { setViewTreeSavedStateRegistryOwner(it) }
        }
        rootView.addView(frameLayout)

        
        composeView.tag = WeakReference(frameLayout)
    }

    fun removeComposeViewFromActivity(activity: Activity, composeView: androidx.compose.ui.platform.ComposeView) {
        val rootView = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        val frameLayout = (composeView.tag as? WeakReference<*>)?.get() as? android.widget.FrameLayout
        frameLayout?.let {
            rootView.removeView(it)
        }
    }
}