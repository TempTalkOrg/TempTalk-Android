package com.difft.android.base.utils

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.os.Build
import com.difft.android.base.log.lumberjack.L
import java.util.concurrent.ConcurrentHashMap


















object ForegroundServiceStarter {

    




    private val fullStackLoggedOnce: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    @JvmOverloads
    fun startForegroundSafely(
        service: Service,
        id: Int,
        notification: Notification,
        foregroundServiceType: Int = 0,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && foregroundServiceType != 0) {
                service.startForeground(id, notification, foregroundServiceType)
            } else {
                service.startForeground(id, notification)
            }
            true
        } catch (e: IllegalStateException) {
            
            
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            ) {
                val key = service.javaClass.simpleName
                if (fullStackLoggedOnce.add(key)) {
                    L.w {
                        "[$key] startForeground refused " +
                                "state=BG cause=FgsNotAllowed mAllowStartForeground=false " +
                                "action=stopSelf ${e.stackTraceToString()}"
                    }
                } else {
                    
                    L.i {
                        "[$key] startForeground refused (repeated, " +
                                "see first-occurrence trace) cause=FgsNotAllowed action=stopSelf"
                    }
                }
                service.stopSelf()
                false
            } else {
                
                
                
                throw e
            }
        }
    }
}
