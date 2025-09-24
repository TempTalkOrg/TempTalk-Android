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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import com.difft.android.base.call.CallActionType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.PackageUtil
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallActivity
import com.difft.android.call.LCallManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


/**
 * A foreground service will keep the app alive in the background.
 *
 * Beginning with Android 14, foreground service types are required.
 * This service declares the mediaPlayback, camera, and microphone types
 * in the AndroidManifest.xml.
 *
 * This ensures that the app will continue to be able to playback media and
 * access the camera/microphone in the background. Apps that don't declare
 * these will run into issues when trying to access them from the background.
 */

open class ForegroundService : Service() {

    private var isForegroundStarted = false

    @Volatile
    private var isCallMsgListenerStarted = false

    private val serviceExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())


    override fun onCreate() {
        super.onCreate()
        L.i { "[Call] +++ ForegroundService onCreate +++" }
        isServiceRunning = true
        createNotificationChannel()
        addCallControlMessageListener()
    }

    private fun updateNotification(useCallStyle: Boolean) {

        val activityIntent = CallIntent.Builder(this, LCallActivity::class.java)
            .withAction(CallIntent.Action.BACK_TO_CALL)
            .withIntentFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK).build()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            activityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder =
            NotificationCompat.Builder(this, CHANNEL_CONFIG_NAME_ONGOING_CALL)
                .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
                .setContentTitle(PackageUtil.getAppName())
                .setContentText("Your call is in progress").setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true) // Full-screen intent

        // Apply CallStyle if needed
        if (useCallStyle) {
            val person =
                Person.Builder().setName(PackageUtil.getAppName()).build()

            val hangUpIntent = Intent().apply {
                action = LCallActivity.ACTION_IN_CALLING_CONTROL
                setPackage(ApplicationHelper.instance.packageName)
                putExtra(
                    LCallActivity.EXTRA_CONTROL_TYPE,
                    CallActionType.DECLINE.type
                )
            }
            val hangUpPendingIntent = PendingIntent.getBroadcast(
                this,
                0,
                hangUpIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            builder.setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    person, hangUpPendingIntent
                )
            )
        }

        val notification = builder.build()
        // Update the notification without restarting the foreground service
        val notificationManager =
            getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(DEFAULT_NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if(!isForegroundStarted) {
            L.i { "[Call] ForegroundService +++ start Foreground Service +++" }
            isForegroundStarted = true
            val notification: Notification? = buildForegroundNotification()
            try {
                startForeground(DEFAULT_NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                L.e { "[Call] ForegroundService Failed to start foreground service: ${e.message}" }
                stopSelf() // Stop the service if starting foreground fails, or handle as needed
            }
        }

        if (intent != null && intent.action != null) {
            serviceExecutor.execute {
                when (intent.action) {
                    ACTION_UPDATE_NOTIFICATION -> {
                        L.i { "[Call] ForegroundService +++ Updating Notification +++" }
                        if(LCallActivity.isInCalling()){
                            val useCallStyle =
                                intent.getBooleanExtra(EXTRA_USE_CALL_STYLE, false)
                            updateNotification(useCallStyle)
                        }
                    }
                    ACTION_STOP_SERVICE -> {
                        L.i { "[Call] ForegroundService +++ Stopping Foreground Service +++" }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }

        return START_NOT_STICKY
    }


    override fun onDestroy() {
        super.onDestroy()
        L.d { "[Call] ForegroundService onDestroy" }
        serviceScope.cancel()
        isCallMsgListenerStarted = false
        isServiceRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // not used.
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        L.i { "[Call] ForegroundService onTaskRemoved rootIntent:$rootIntent" }
        if(rootIntent != null && rootIntent.action == Intent.ACTION_MAIN){
            sendBroadcast(createAppTaskEndIntent())
        }
    }


    private fun addCallControlMessageListener() {
        if(!isCallMsgListenerStarted){
            isCallMsgListenerStarted = true
            serviceScope.launch {
                LCallManager.controlMessage.collect {
                    handleControlMessage(it)
                }
            }
        }
    }

    private fun createAppTaskEndIntent() = Intent().apply {
        action = LCallActivity.ACTION_IN_CALLING_CONTROL
        setPackage(ApplicationHelper.instance.packageName)
        putExtra(LCallActivity.EXTRA_CONTROL_TYPE, CallActionType.DECLINE.type)
    }


    private fun sendCallControlBroadcast(context: Context, actionType: CallActionType, roomId: String) {
        val intent = Intent(LCallActivity.ACTION_IN_CALLING_CONTROL).apply {
            setPackage(ApplicationHelper.instance.packageName)
            putExtra(LCallActivity.EXTRA_CONTROL_TYPE, actionType.type)
            putExtra(LCallActivity.EXTRA_PARAM_ROOM_ID, roomId)
        }
        context.sendBroadcast(intent)
    }


    private fun handleControlMessage( controlMessage: LCallManager.ControlMessage?) {
        if (controlMessage == null) return
        L.i { "[Call] ForegroundService handleControlMessage ${controlMessage.actionType} roomId:${controlMessage.roomId}}" }
        when (controlMessage.actionType) {
            CallActionType.CALLEND, CallActionType.HANGUP, CallActionType.REJECT -> {
                sendCallControlBroadcast(this, controlMessage.actionType, controlMessage.roomId)
            }
            else -> { }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_CONFIG_NAME_ONGOING_CALL,
                DEFAULT_CHANNEL_ID,  // User-visible name
                NotificationManager.IMPORTANCE_DEFAULT // or HIGH, LOW, MIN
            )
            val manager = getSystemService<NotificationManager?>(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel)
            }
        }
    }

    private fun buildForegroundNotification(): Notification {
        // Create a simple notification for the foreground service
        return NotificationCompat.Builder(this, CHANNEL_CONFIG_NAME_ONGOING_CALL)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText("Your call is in progress").setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        var isServiceRunning = false
        const val DEFAULT_NOTIFICATION_ID = 3456
        const val DEFAULT_CHANNEL_ID = "tt_livekit_foreground"
        const val CHANNEL_CONFIG_NAME_ONGOING_CALL = "ONGOING_CALL"
        const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        const val ACTION_UPDATE_NOTIFICATION = "ACTION_UPDATE_NOTIFICATION"
        const val EXTRA_USE_CALL_STYLE = "EXTRA_USE_CALL_STYLE"
    }
}
