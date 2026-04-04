package org.thoughtcrime.securesms.util

import android.app.AlarmManager
import android.app.ForegroundServiceStartNotAllowedException
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.application
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.messages.MessageForegroundService
import util.AppForegroundObserver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * Helps start foreground services from the background.
 */
object ForegroundServiceUtil {
    private val updateMutex: ReentrantLock = ReentrantLock()
    private var activeLatch: CountDownLatch? = null

    private val DEFAULT_TIMEOUT: Long = 1.minutes.inWholeMilliseconds

    /**
     * A simple wrapper around [ContextCompat.startForegroundService], but makes the possible failure part of the contract by forcing the caller to handle the
     * [UnableToStartException].
     */
    @JvmStatic
    @Throws(UnableToStartException::class)
    fun start(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT < 31) {
            ContextCompat.startForegroundService(context, intent)
        } else {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: IllegalStateException) {
                if (e is ForegroundServiceStartNotAllowedException) {
                    L.e(e) { "[ForegroundServiceUtil] Unable to start foreground service:" }
                    throw UnableToStartException(e)
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * Does its best to start a foreground service, including possibly blocking and waiting until we are able to.
     * However, it is always possible that the attempt will fail, so be sure to handle the [UnableToStartException].
     *
     * @param timeout The maximum time you're willing to wait to create the conditions for a foreground service to start.
     */
    @JvmOverloads
    @JvmStatic
    @Throws(UnableToStartException::class)
    @WorkerThread
    fun startWhenCapable(context: Context, intent: Intent, timeout: Long = DEFAULT_TIMEOUT) {
        try {
            start(context, intent)
        } catch (e: UnableToStartException) {
            L.e(e) { "[ForegroundServiceUtil] Failed to start normally. Blocking and then trying again." }
            blockUntilCapable(context, timeout)
            start(context, intent)
        }
    }

    /**
     * Identical to [startWhenCapable], except in this case we just throw if we're unable to start the service. Should only be used if there's no good way
     * to gracefully handle the exception (or if you know for sure it won't get thrown).
     *
     * @param timeout The maximum time you're willing to wait to create the conditions for a foreground service to start.
     */
    @JvmOverloads
    @JvmStatic
    @WorkerThread
    fun startWhenCapableOrThrow(context: Context, intent: Intent, timeout: Long = DEFAULT_TIMEOUT) {
        try {
            startWhenCapable(context, intent, timeout)
        } catch (e: UnableToStartException) {
            throw IllegalStateException(e)
        }
    }

    /**
     * Waits until we're capable of starting a foreground service.
     * @return True if you should expect to be able to start a foreground service, otherwise false. Please note that this isn't *guaranteed*, just what we believe
     *         given the information we have.
     */
    private fun blockUntilCapable(context: Context, timeout: Long = DEFAULT_TIMEOUT): Boolean {
        val alarmManager = ServiceUtil.getAlarmManager(context)

        if (Build.VERSION.SDK_INT < 31 || AppForegroundObserver.isForegrounded()) {
            return true
        }

        if (!alarmManager.canScheduleExactAlarms()) {
            return false
        }

        val latch: CountDownLatch? = updateMutex.withLock {
            if (activeLatch == null) {
                if (alarmManager.canScheduleExactAlarms()) {
                    activeLatch = CountDownLatch(1)
                    val pendingIntent = PendingIntent.getBroadcast(context, 0, Intent(context, Receiver::class.java), if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                    alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 1000, pendingIntent)
                } else {
                    L.e { "[ForegroundServiceUtil] Unable to schedule alarm:" }
                }
            }
            activeLatch
        }

        if (latch != null) {
            try {
                if (!latch.await(timeout, TimeUnit.MILLISECONDS)) {
                    L.w { "[ForegroundServiceUtil] Time ran out waiting for foreground" }
                    return false
                }
            } catch (e: InterruptedException) {
                L.w { "[ForegroundServiceUtil] Interrupted while waiting for foreground" }
            }
        }

        return true
    }

    fun stopService(serviceClass: Class<out Service>) {
        try {
            val intent = Intent(application, serviceClass)
            application.stopService(intent)
        } catch (e: Exception) {
            L.e(e) { "[ForegroundServiceUtil] Unable to stop service:" }
        }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateMutex.withLock {
                activeLatch?.countDown()
                activeLatch = null
            }
        }
    }
}

class UnableToStartException(cause: Throwable) : Exception(cause)
