package com.difft.android.call.service

import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.call.LCallToChatController
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton





















@Singleton
class OngoingCallServiceController @Inject constructor(
    private val application: Application,
    private val callToChatController: LCallToChatController,
) {
    
    private var startIssued = false
    private var foregroundRetry: AppForegroundObserver.Listener? = null

    
    @MainThread
    fun start(id: Long) {
        ForegroundService.ownerSessionId = id
        startIssued = false
        if (callToChatController.isAppForegrounded()) {
            issueStart(id)
        } else {
            L.i { "[Call] FGS start deferred id=$id: app not foreground" }
            armForegroundRetry(id)
        }
    }

    
    @MainThread
    fun ensureStarted(id: Long) {
        if (ForegroundService.ownerSessionId == id && !startIssued) issueStart(id)
    }

    
    @MainThread
    fun onPromoteFailed(id: Long) {
        if (id == ForegroundService.NO_OWNER || ForegroundService.ownerSessionId != id) return
        L.w { "[Call] FGS promote failed id=$id, start re-armed" }
        startIssued = false
        armForegroundRetry(id)
    }

    
    @MainThread
    fun stop(id: Long, tag: String) {
        val owner = ForegroundService.ownerSessionId
        if (owner != id) {
            L.i { "[Call] FGS stop($tag) skipped: owner=$owner id=$id" }
            return
        }
        ForegroundService.ownerSessionId = ForegroundService.NO_OWNER
        startIssued = false
        disarmForegroundRetry()
        val pending = ForegroundService.pendingTypeUpdates
        if (!ForegroundService.isServiceRunning || pending > 0) {
            L.i { "[Call] FGS stop($tag) id=$id left to self-stop running=${ForegroundService.isServiceRunning} pendingTypeUpdates=$pending" }
            return
        }
        L.i { "[Call] FGS stop($tag) id=$id" }
        try {
            application.stopService(serviceIntent())
        } catch (e: Exception) {
            L.e { "[Call] FGS stop($tag) id=$id failed: ${e.stackTraceToString()}" }
        }
    }

    




    @MainThread
    fun updateServiceType(id: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (ForegroundService.ownerSessionId != id || !ForegroundService.isServiceRunning) {
            L.i { "[Call] FGS type update skipped id=$id owner=${ForegroundService.ownerSessionId} running=${ForegroundService.isServiceRunning}" }
            return
        }
        if (NotificationChannelSpecs.preflightForegroundChannel(application, SERVICE_TAG) == null) return
        ForegroundService.pendingTypeUpdates++
        val reached = try {
            application.startForegroundService(serviceIntent(id).setAction(ForegroundService.ACTION_UPDATE_SERVICE_TYPE)) != null
        } catch (e: Exception) {
            L.e { "[Call] FGS type update id=$id failed: ${e.stackTraceToString()}" }
            false
        }
        if (!reached) {
            ForegroundService.pendingTypeUpdates = (ForegroundService.pendingTypeUpdates - 1).coerceAtLeast(0)
        }
    }

    



    private fun issueStart(id: Long) {
        if (NotificationChannelSpecs.preflightForegroundChannel(application, SERVICE_TAG) == null) {
            onStartNotIssued(id)
            return
        }
        startIssued = true
        disarmForegroundRetry()
        L.i { "[Call] FGS start id=$id" }
        val issued = try {
            callToChatController.startForegroundService(application, serviceIntent(id)) {
                Handler(Looper.getMainLooper()).post { onDeferredStartFailed(id) }
            }
        } catch (e: Exception) {
            L.e { "[Call] FGS start id=$id failed: ${e.stackTraceToString()}" }
            false
        }
        if (!issued) onStartNotIssued(id)
    }

    
    @MainThread
    internal fun onDeferredStartFailed(id: Long) {
        if (ForegroundService.ownerSessionId != id || ForegroundService.isServiceRunning) return
        onStartNotIssued(id)
    }

    private fun onStartNotIssued(id: Long) {
        L.w { "[Call] FGS start not issued id=$id, retry armed" }
        startIssued = false
        armForegroundRetry(id)
    }

    



    private fun armForegroundRetry(id: Long) {
        disarmForegroundRetry()
        val listener = object : AppForegroundObserver.Listener {
            var armed = false
            override fun onForeground() {
                if (armed) ensureStarted(id)
            }
        }
        foregroundRetry = listener
        AppForegroundObserver.addListener(listener)
        listener.armed = true
    }

    private fun disarmForegroundRetry() {
        foregroundRetry?.let(AppForegroundObserver::removeListener)
        foregroundRetry = null
    }

    private fun serviceIntent(id: Long? = null) = Intent(application, ForegroundService::class.java).apply {
        id?.let { putExtra(ForegroundService.EXTRA_SESSION_ID, it) }
    }

    private companion object {
        const val SERVICE_TAG = "ForegroundService"
    }
}
