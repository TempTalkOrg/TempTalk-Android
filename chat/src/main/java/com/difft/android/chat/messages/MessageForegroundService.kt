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

    
    private var refusalRecorded = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("MessageForegroundService") + dbKeyFailSoftExceptionHandler)

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        L.i { "[MessageForegroundService] onCreate()" }
        
        if (postForegroundNotification()) {
            isRunning = true
            if (!refusalRecorded) runCatching { healthCounters().onServiceStarted() }
            
            
            
            if (!refusalRecorded) startConnection()
        }

        
        WCDBUpdateService.updatingRooms()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        L.i { "[MessageForegroundService] onStartCommand() startId=$startId sticky=${intent == null}" }

        if (!postForegroundNotification()) return START_NOT_STICKY

        startConnection()

        return START_STICKY
    }

    
    private fun startConnection() {
        serviceScope.launch {
            
            
            
            
            
            
            
            
            
            
            if (!isActive) {
                L.w { "[MessageForegroundService] launch body skipped — service already stopping" }
                return@launch
            }
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    applicationContext, ServiceEntryPoint::class.java
                )
                
                
                
                runCatching { entryPoint.messageServiceManager() }
                    .onFailure { L.e { "[MessageForegroundService] Failed to init MessageServiceManager: ${it.stackTraceToString()}" } }
                entryPoint.webSocketManager().start()
            } catch (e: Exception) {
                L.e { "[MessageForegroundService] Failed to start WebSocket: ${e.stackTraceToString()}" }
            }
        }
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

    




    private fun postForegroundNotification(): Boolean {
        val channelId = NotificationChannelSpecs.serviceForegroundChannel(this, SERVICE_TAG)
        if (channelId == null) {
            stopSelf()
            return false
        }
        val started = ForegroundServiceStarter.startForegroundSafely(this, FOREGROUND_ID, createNotification(channelId))
        
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
