package com.difft.android.test.shadows

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService







@Implements(Service::class)
class ShadowThrowingService : ShadowService() {

    companion object {
        
        @Volatile
        var throwOnNext: Boolean = false

        


        @Volatile
        var throwOnNextPlainIse: IllegalStateException? = null

        @Volatile
        var startForegroundCallCount: Int = 0

        fun reset() {
            throwOnNext = false
            throwOnNextPlainIse = null
            startForegroundCallCount = 0
        }

        


        
        @SuppressLint("NewApi")
        internal fun newFgsnae(message: String): ForegroundServiceStartNotAllowedException {
            return try {
                ForegroundServiceStartNotAllowedException(message)
            } catch (t: Throwable) {
                val ctor = ForegroundServiceStartNotAllowedException::class.java
                    .getDeclaredConstructor(String::class.java)
                    .apply { isAccessible = true }
                ctor.newInstance(message) as ForegroundServiceStartNotAllowedException
            }
        }
    }

    @Implementation
    override fun startForeground(id: Int, notification: Notification?) {
        startForegroundCallCount++
        throwOnNextPlainIse?.let {
            throwOnNextPlainIse = null
            throw it
        }
        if (throwOnNext) {
            throwOnNext = false
            throw newFgsnae("simulated mAllowStartForeground false")
        }
        
    }

    @Implementation
    override fun startForeground(id: Int, notification: Notification?, foregroundServiceType: Int) {
        startForegroundCallCount++
        throwOnNextPlainIse?.let {
            throwOnNextPlainIse = null
            throw it
        }
        if (throwOnNext) {
            throwOnNext = false
            throw newFgsnae("simulated mAllowStartForeground false")
        }
    }
}
