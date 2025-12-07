package com.difft.android.call.util

import android.app.Activity
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