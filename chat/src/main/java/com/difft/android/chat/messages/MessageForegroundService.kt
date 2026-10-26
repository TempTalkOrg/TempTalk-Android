package com.difft.android.chat.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import com.difft.android.base.monitor.FgsStartFailure
import com.difft.android.base.monitor.ForegroundServiceFailureRecorder
import com.difft.android.base.monitor.KeepAliveHealthCounters
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.dbKeyFailSoftExceptionHandler
import com.difft.android.chat.R
import com.difft.android.chat.websocket.WebSocketManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.difft.app.database.WCDBUpdateService

/**
 * Background WebSocket connection foreground service.
 */
class MessageForegroundService : Service() {

    companion object {
        const val FOREGROUND_ID = 313399

        private const val SERVICE_TAG = "MessageForegroundService"

        @Volatile
        var isRunning = false
            private set
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun webSocketManager(): WebSocketManager

        fun messageServiceManager(): MessageServiceManager

        fun foregroundServiceFailureRecorder(): ForegroundServiceFailureRecorder

        fun keepAliveHealthCounters(): KeepAliveHealthCounters
    }

    // #1328 health counter: one start-foreground refusal per service instance.
    private var refusalRecorded = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("MessageForegroundService") + dbKeyFailSoftExceptionHandler)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        L.i { "[MessageForegroundService] onCreate()" }
        // Without a channel the service has stopped itself: neither running nor counted as started.
        if (postForegroundNotification()) {
            isRunning = true
            if (!refusalRecorded) runCatching { healthCounters().onServiceStarted() }
        }

        // Bare call (NOT serviceScope.launch): collector anchors to WCDBUpdateService's process scope and must outlive this Service. Idempotent.
        WCDBUpdateService.updatingRooms()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        L.d { "[MessageForegroundService] onStartCommand()" }

        if (!postForegroundNotification()) return START_NOT_STICKY

        serviceScope.launch {
            // F-H1 safety: if startForeground above failed via FGSNAE, the helper
            // already called stopSelf(). onDestroy() will run and trigger
            // serviceScope.cancel() at some point, but until then this launch body
            // may proceed to invoke the @Synchronized non-suspend
            // WebSocketManager.start() (which does NOT consult coroutine isActive,
            // runs synchronously to completion on Dispatchers.IO, sets
            // isStarted=true). To avoid starting WebSocket on a Service that is
            // already on its way to onDestroy, check isActive first.
            //
            // This is the FIRST line of the launch body; control flow of
            // onStartCommand itself is unchanged.
            if (!isActive) {
                L.w { "[MessageForegroundService] launch body skipped — service already stopping" }
                return@launch
            }
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext, ServiceEntryPoint::class.java
                )
                // Instantiate the manager so alarm-chain self-healing is active even on a
                // START_STICKY revival where no other component touches it. Isolated so a
                // manager-graph construction failure cannot block the WebSocket start below.
                runCatching { entryPoint.messageServiceManager() }
                    .onFailure { L.e { "[MessageForegroundService] Failed to init MessageServiceManager: ${it.stackTraceToString()}" } }
                entryPoint.webSocketManager().start()
            } catch (e: Exception) {
                L.e { "[MessageForegroundService] Failed to start WebSocket: ${e.stackTraceToString()}" }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        runCatching { healthCounters().onServiceStopped() }
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        L.i { "[MessageForegroundService] onDestroy()" }
    }

    private fun healthCounters(): KeepAliveHealthCounters =
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java).keepAliveHealthCounters()

    /**
     * Promotes on a verified channel only (#1354). With none, stops without `startForeground` and
     * returns false; a sticky restart reaches here with no pending foreground requirement, so the
     * stop is safe. A refused promotion is handled by the helper and still returns true.
     */
    private fun postForegroundNotification(): Boolean {
        val channelId = NotificationChannelSpecs.serviceForegroundChannel(this, SERVICE_TAG)
        if (channelId == null) {
            stopSelf()
            return false
        }
        val started = ForegroundServiceStarter.startForegroundSafely(this, FOREGROUND_ID, createNotification(channelId))
        // onCreate and onStartCommand both land here; count a refusal once.
        if (!started && !refusalRecorded) {
            refusalRecorded = true
            runCatching {
                EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
                    .foregroundServiceFailureRecorder().record(FgsStartFailure.STARTFG_REFUSED)
            }
        }
        return true
    }

    private fun createNotification(channelId: String): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(ResUtils.getString(R.string.background_connection_enabled))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setWhen(0)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentIntent(createSettingsPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    private fun createSettingsPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_BACKGROUND_CONNECTION_SETTINGS)
            data = "app://notification/settings/${System.currentTimeMillis()}".toUri()
        }
        return PendingIntent.getActivity(
            this, FOREGROUND_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
