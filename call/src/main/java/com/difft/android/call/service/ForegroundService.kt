/*
 * Copyright 2024 LiveKit, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.difft.android.call.service

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ForegroundServiceStarter
import com.difft.android.base.utils.PackageUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.data.CallStatus
import com.difft.android.call.receiver.CallNotificationActionReceiver
import com.difft.android.call.state.OnGoingCallStateManager
import com.difft.android.base.notification.NotificationChannelSpecs
import com.difft.android.base.notification.SystemProps
import com.difft.android.call.util.PermissionUtil
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
















private val notificationTemplateDropsCallStyleActions: Boolean by lazy {
    
    val drops = Build.MANUFACTURER.lowercase(Locale.ROOT) == MANUFACTURER_XIAOMI
    
    
    
    
    
    L.i {
        "[Call] ForegroundService notification template check manufacturer=${Build.MANUFACTURER}" +
            " brand=${Build.BRAND} model=${Build.MODEL}" +
            " miuiVersion=${SystemProps.get("ro.miui.ui.version.name")}" +
            " hyperOsVersion=${SystemProps.get("ro.mi.os.version.name")}" +
            " dropsCallStyleActions=$drops"
    }
    drops
}

private const val MANUFACTURER_XIAOMI = "xiaomi"

open class ForegroundService : Service() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ServiceEntryPoint {
        fun onGoingCallStateManager(): OnGoingCallStateManager
        fun ongoingCallServiceController(): OngoingCallServiceController
    }

    private val entryPoint by lazy {
        EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
    }

    private val onGoingCallStateManager: OnGoingCallStateManager by lazy {
        entryPoint.onGoingCallStateManager()
    }

    @Volatile
    private var isForegroundStarted = false

    private val foregroundStarted = MutableStateFlow(false)

    



    private fun setForegroundStarted(value: Boolean) {
        isForegroundStarted = value
        foregroundStarted.value = value
    }

    




    @Volatile
    private var currentNotification: Notification? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    
    private var lastStartId = 0


    @RequiresApi(30)
    private fun getServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val serviceTypes = mutableListOf<String>("DATA_SYNC")

        if (PermissionUtil.hasAll(this, Manifest.permission.RECORD_AUDIO)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            serviceTypes.add("MICROPHONE")
        }

        if (PermissionUtil.hasAll(this, Manifest.permission.CAMERA)) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            serviceTypes.add("CAMERA")
        }

        L.d { "[Call] ForegroundService serviceType: ${serviceTypes.joinToString("|")}" }
        return type
    }

    override fun onCreate() {
        super.onCreate()
        L.i { "[Call] +++ ForegroundService onCreate +++" }
        observeCallState()
    }

    













    private fun observeCallState() {
        serviceScope.launch(Dispatchers.Main) {
            callNotificationStates(
                status = onGoingCallStateManager.callStatus,
                connectedAt = onGoingCallStateManager.callConnectedAt,
                foregroundStarted = foregroundStarted,
                roomId = onGoingCallStateManager.currentRoomId,
                isCallEnding = { onGoingCallStateManager.isInCallEnding() },
            ).collect { state ->
                if (state == null) return@collect
                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getServiceType() else 0
                val posted = promote(type) { channelId -> buildOngoingCallNotification(state.status, state.connectedAt, channelId) }
                if (posted != null) {
                    currentNotification = posted
                    L.i {
                        "[Call] ForegroundService notification updated status=${state.status}" +
                            " connectedAt=${state.connectedAt}" +
                            " style=${if (notificationTemplateDropsCallStyleActions) "inbox+action" else "callStyle"}" +
                            " chronometer=${notificationTemplateDropsCallStyleActions && state.connectedAt > 0}"
                    }
                } else {
                    
                    setForegroundStarted(false)
                }
            }
        }
    }

    










    private fun promote(type: Int, build: (channelId: String) -> Notification): Notification? {
        val channelId = NotificationChannelSpecs.serviceForegroundChannel(this, SERVICE_TAG)
        if (channelId == null) {
            onPromotionFailed(stop = true)
            return null
        }
        val notification = build(channelId)
        val dataSync = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val retryAsDataSync = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && type != dataSync
        val promoted = promoteOrNull(notification, type)
            ?: if (retryAsDataSync) promoteOrNull(notification, dataSync) else null
        if (promoted == true) return notification
        onPromotionFailed(stop = promoted == null)
        return null
    }

    private fun onPromotionFailed(stop: Boolean) {
        val owner = ownerSessionId
        isServiceRunning = false
        if (owner != NO_OWNER) {
            Handler(Looper.getMainLooper()).post { entryPoint.ongoingCallServiceController().onPromoteFailed(owner) }
        }
        if (stop) stopSelfResult(lastStartId)
    }

    
    private fun currentOrStarting(channelId: String): Notification =
        currentNotification?.takeIf { it.channelId == channelId } ?: buildStartingNotification(channelId)

    
    private fun promoteOrNull(notification: Notification, type: Int): Boolean? = try {
        ForegroundServiceStarter.startForegroundSafely(this, DEFAULT_NOTIFICATION_ID, notification, type)
    } catch (e: SecurityException) {
        L.w { "[Call] ForegroundService type denied session=$ownerSessionId type=$type: ${e.message}" }
        null
    }

    




    private fun callActivityPendingIntent(): PendingIntent {
        val activityIntent = CallIntent.Builder(this, LCallActivity::class.java)
            .withAction(CallIntent.Action.BACK_TO_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK).build()

        return PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    
    private fun hangUpPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        this,
        REQUEST_CODE_HANG_UP,
        Intent(this, CallNotificationActionReceiver::class.java)
            .setAction(CallNotificationActionReceiver.ACTION_HANG_UP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildOngoingCallNotification(status: CallStatus, connectedAt: Long, channelId: String): Notification {

        val pendingIntent = callActivityPendingIntent()
        val hangUpPendingIntent = hangUpPendingIntent()

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(getString(contentTextRes(status)))
            .setOngoing(true)
            
            
            
            .setWhen(connectedAt)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            
            
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) 

        if (notificationTemplateDropsCallStyleActions) {
            builder
                .addAction(
                    com.difft.android.call.R.drawable.call_ic_hangup,
                    getString(com.difft.android.call.R.string.call_notification_hang_up),
                    hangUpPendingIntent
                )
                .setStyle(NotificationCompat.InboxStyle())
                
                
                
                
                .setShowWhen(connectedAt > 0)
                .setUsesChronometer(connectedAt > 0)
        } else {
            val person = Person.Builder().setName(PackageUtil.getAppName()).build()
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUpPendingIntent))
        }

        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        
        
        
        
        
        
        
        lastStartId = startId
        val isTypeUpdate = intent?.action == ACTION_UPDATE_SERVICE_TYPE
        
        if (isTypeUpdate) pendingTypeUpdates = (pendingTypeUpdates - 1).coerceAtLeast(0)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) getServiceType() else 0
        val posted = promote(type, ::currentOrStarting)
        if (posted != null) {
            currentNotification = posted
            isServiceRunning = true
            if (!isForegroundStarted) {
                L.i { "[Call] ForegroundService +++ start Foreground Service +++" }
                setForegroundStarted(true)
            }
        } else {
            setForegroundStarted(false)
            return START_NOT_STICKY
        }

        val owner = ownerSessionId
        if (owner == NO_OWNER) {
            stopWithoutOwner(startId)
            return START_NOT_STICKY
        }
        val requested = intent?.getLongExtra(EXTRA_SESSION_ID, NO_OWNER) ?: NO_OWNER
        if (requested != NO_OWNER && requested != owner) {
            L.i { "[Call] ForegroundService late start session=$requested owner=$owner" }
        }

        if (isTypeUpdate) {
            
            
            
            L.i { "[Call] ForegroundService +++ Updating Service Type +++" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                updateServiceType()
            }
        }

        return START_NOT_STICKY
    }

    




    private fun stopWithoutOwner(startId: Int) {
        L.i { "[Call] ForegroundService no owner, stopping startId=$startId" }
        
        setForegroundStarted(false)
        
        currentNotification = null
        isServiceRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
    }


    override fun onDestroy() {
        super.onDestroy()
        L.d { "[Call] ForegroundService onDestroy" }
        cancelNotification()
        serviceScope.cancel()
        isServiceRunning = false
        pendingTypeUpdates = 0
    }

    private fun cancelNotification() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(DEFAULT_NOTIFICATION_ID)
        } catch (e: Exception) {
            L.w { "[Call] ForegroundService Failed to cancel notification: ${e.message}" }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null 
    }

    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        L.i { "[Call] ForegroundService onTaskRemoved rootAction=${rootIntent?.action}" }
    }

    




    private fun buildStartingNotification(channelId: String): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(getString(com.difft.android.call.R.string.call_connecting_title))
            .setOngoing(true)
            
            
            
            
            .setContentIntent(callActivityPendingIntent())
            
            
            
            
            .setPriority(NotificationCompat.PRIORITY_MIN)
            
            .setOnlyAlertOnce(true)
        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    



    @RequiresApi(30)
    private fun updateServiceType() {
        if (isForegroundStarted) {
            val newServiceType = getServiceType()
            
            
            if (promote(newServiceType, ::currentOrStarting) != null) {
                L.i { "[Call] ForegroundService service type updated successfully" }
            } else {
                
                setForegroundStarted(false)
            }
        }
    }

    companion object {
        
        @Volatile
        internal var isServiceRunning = false

        
        @Volatile
        internal var ownerSessionId: Long = NO_OWNER

        
        internal var pendingTypeUpdates = 0

        internal const val NO_OWNER = -1L
        internal const val EXTRA_SESSION_ID = "call_session_id"
        const val DEFAULT_NOTIFICATION_ID = 3456
        private const val SERVICE_TAG = "ForegroundService"
        const val ACTION_UPDATE_SERVICE_TYPE = "ACTION_UPDATE_SERVICE_TYPE"
        private const val REQUEST_CODE_HANG_UP = 1
    }
}
