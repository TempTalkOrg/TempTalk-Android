package com.difft.android.chat.messages

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.PackageUtil
import com.difft.android.base.utils.ResUtils
import com.difft.android.chat.R
import com.difft.android.chat.websocket.WebSocketManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background WebSocket connection foreground service.
 */
class MessageForegroundService : Service() {

    companion object {
        const val FOREGROUND_ID = 313399
        private const val CHANNEL_CONFIG_NAME_BACKGROUND = "BACKGROUND"

        @Volatile
        var isRunning = false
            private set
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun webSocketManager(): WebSocketManager
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        L.i { "[MessageForegroundService] onCreate()" }
        postForegroundNotification()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        L.i { "[MessageForegroundService] onStartCommand()" }

        postForegroundNotification()

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
                entryPoint.webSocketManager().start()
            } catch (e: Exception) {
                L.e { "[MessageForegroundService] Failed to start WebSocket: ${e.stackTraceToString()}" }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
        L.i { "[MessageForegroundService] onDestroy()" }
    }

    private fun postForegroundNotification() {
        ForegroundServiceStarter.startForegroundSafely(this, FOREGROUND_ID, createNotification())
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_CONFIG_NAME_BACKGROUND)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(ResUtils.getString(R.string.background_connection_enabled))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setWhen(0)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentIntent(createSettingsPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun createSettingsPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_BACKGROUND_CONNECTION_SETTINGS)
            data = android.net.Uri.parse("app://notification/settings/${System.currentTimeMillis()}")
        }
        return PendingIntent.getActivity(
            this, FOREGROUND_ID, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }
}
