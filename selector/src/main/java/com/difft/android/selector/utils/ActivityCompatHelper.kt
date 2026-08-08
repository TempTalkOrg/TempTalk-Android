package com.difft.android.selector.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

import androidx.fragment.app.FragmentActivity

object ActivityCompatHelper {

    @JvmStatic
    fun isDestroy(activity: Activity?): Boolean {
        if (activity == null) {
            return true
        }
        return activity.isFinishing || activity.isDestroyed
    }

    @JvmStatic
    fun checkFragmentNonExits(activity: FragmentActivity, fragmentTag: String?): Boolean {
        if (isDestroy(activity)) {
            return false
        }
        val fragment = activity.supportFragmentManager.findFragmentByTag(fragmentTag)
        return fragment == null
    }

    @JvmStatic
    fun assertValidRequest(context: Context?): Boolean {
        if (context is Activity) {
            return !isDestroy(context)
        } else if (context is ContextWrapper) {
            val baseContext = context.baseContext
            if (baseContext is Activity) {
                return !isDestroy(baseContext)
            }
        }
        return true
    }
}
