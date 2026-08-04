package com.difft.android.selector.pictureselector

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

object ImageLoaderUtils {
    @JvmStatic
    fun assertValidRequest(context: Context): Boolean {
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

    private fun isDestroy(activity: Activity?): Boolean {
        if (activity == null) {
            return true
        }
        return activity.isFinishing || activity.isDestroyed
    }
}
