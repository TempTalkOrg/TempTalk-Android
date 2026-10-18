package com.difft.android.call.service

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.annotation.MainThread
import com.difft.android.base.log.lumberjack.L
import com.difft.android.call.LCallToChatController
import util.AppForegroundObserver
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Drives the ongoing-call [ForegroundService] for one call session at a time, keyed by the session id
 * ([ForegroundService.ownerSessionId]): only the owning session's stop stops it, so an ended session
 * can never take down the service of the call that replaced it.
 *
 * The first start is issued only while the app is in the foreground: a microphone/camera-typed start
 * from the background is denied by the platform when the service promotes itself. A deferred start
 * is issued by [ensureStarted] (the call page's `onResume`) or by the next app-foreground transition.
 *
 * A stop never brings the service down while a type-update start is still on its way to it
 * ([ForegroundService.pendingTypeUpdates]): that start requires a promotion, and a service destroyed
 * before making it crashes the app. Such a start, like any start that reaches the service after its
 * owner stopped it, promotes and then stops the service itself.
 *
 * Every method runs on main; every platform call is caught and logged.
 */
@Singleton
class OngoingCallServiceController @Inject constructor(
    private val application: Application,
    private val callToChatController: LCallToChatController,
) {
    // Main-confined.
    private var startIssued = false
    private var foregroundRetry: AppForegroundObserver.Listener? = null

    /** Session [id] owns the service from now on; the start is issued now or once the app is foreground. */
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

    /** Issues a deferred or failed start of session [id]; a no-op once issued or after [stop]. */
    @MainThread
    fun ensureStarted(id: Long) {
        if (ForegroundService.ownerSessionId == id && !startIssued) issueStart(id)
    }

    /** The service could not promote itself for session [id]; the next foreground or [ensureStarted] retries. */
    @MainThread
    fun onPromoteFailed(id: Long) {
        if (id == ForegroundService.NO_OWNER || ForegroundService.ownerSessionId != id) return
        L.w { "[Call] FGS promote failed id=$id, start re-armed" }
        startIssued = false
        armForegroundRetry(id)
    }

    /** Stops the service when session [id] owns it; any other session's stop is skipped. */
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

    /**
     * Re-promotes the running service of session [id] with the types the current permissions allow.
     * Issued with the platform start directly: a start the platform refuses must not be deferred and
     * delivered later, when it could not be matched against the pending-update count.
     */
    @MainThread
    fun updateServiceType(id: Long) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (ForegroundService.ownerSessionId != id || !ForegroundService.isServiceRunning) {
            L.i { "[Call] FGS type update skipped id=$id owner=${ForegroundService.ownerSessionId} running=${ForegroundService.isServiceRunning}" }
            return
        }
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
        startIssued = true
        disarmForegroundRetry()
        L.i { "[Call] FGS start id=$id" }
        try {
            callToChatController.startForegroundService(application, serviceIntent(id))
        } catch (e: Exception) {
            startIssued = false
            L.e { "[Call] FGS start id=$id failed: ${e.stackTraceToString()}" }
        }
    }

    /**
     * Retries on the next foreground transition. The listener ignores the state replayed while it is
     * being added: a promotion that failed in the foreground must not be retried in a loop.
     */
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
}
