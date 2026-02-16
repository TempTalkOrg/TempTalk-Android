package com.difft.android.app

import android.app.Activity
import android.app.Application
import android.net.Uri
import android.os.Bundle
import com.difft.android.base.log.lumberjack.L

/**
 * Third-party library Activity guard.
 *
 * Prevents crashes when third-party library Activities are launched directly
 * via abnormal methods (e.g., ADB, Hook frameworks) without required parameters.
 * When missing required startup parameters are detected, the Activity is automatically finished.
 */
object ThirdPartyActivityGuard {

    /**
     * Call this method in Application.onCreate() to register the guard.
     */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                guardUCropActivity(activity)
            }

            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /**
     * Guard for UCropMultipleActivity.
     *
     * UCropMultipleActivity requires dataSource parameter when starting.
     * If launched directly via abnormal methods (ADB/Hook) without parameters, it crashes with:
     * java.lang.IllegalArgumentException: Missing required parameters, count cannot be less than 1
     *
     * Note: UCropActivity has its own protection - it calls setResultError + finish() when
     * parameters are missing, so it won't crash. Only UCropMultipleActivity needs this guard.
     *
     * This method checks required parameters and finishes the Activity if missing to prevent crash.
     */
    private fun guardUCropActivity(activity: Activity) {
        val activityName = activity.javaClass.name

        // Only handle UCropMultipleActivity (UCropActivity has its own protection)
        if (activityName != "com.yalantis.ucrop.UCropMultipleActivity") {
            return
        }

        val intent = activity.intent
        val extras = intent?.extras

        // Check 1: Intent is null or has no extras
        if (extras == null || extras.isEmpty) {
            L.w { "[ThirdPartyActivityGuard] UCropMultipleActivity started without required extras, finishing to prevent crash" }
            activity.finish()
            return
        }

        // Check 2: Missing UCrop's key parameter InputUri
        @Suppress("DEPRECATION")
        val inputUri = intent.getParcelableExtra<Uri>("com.yalantis.ucrop.InputUri")
        if (inputUri == null) {
            L.w { "[ThirdPartyActivityGuard] UCropMultipleActivity started without InputUri, finishing to prevent crash" }
            activity.finish()
            return
        }
    }
}
