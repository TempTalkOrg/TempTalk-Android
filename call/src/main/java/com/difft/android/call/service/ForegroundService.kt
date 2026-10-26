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


/**
 * A foreground service will keep the app alive in the background.
 *
 * Beginning with Android 10 (API 30), foreground service types are required.
 * This service declares the dataSync, camera, and microphone types
 * in the AndroidManifest.xml and dynamically adjusts the service type
 * based on runtime permissions.
 *
 * This ensures that the app will continue to be able to access the camera/microphone
 * in the background. Apps that don't declare these will run into issues when
 * trying to access them from the background.
 *
 */

/**
 * Whether the system's notification template drops the actions a CallStyle notification carries.
 *
 * MIUI/HyperOS renders notifications with its own templates, and the one it picks for a non-media
 * notification has nowhere to put actions: the hang-up button is absent from the shade entirely.
 * Nor does the status-bar call chip CallStyle earns on AOSP ever appear, so on those devices the
 * style costs the only control the user has and returns nothing. Setting the ROM's notification
 * style to "Android" makes the actions appear, which is what confirms the template rather than the
 * notification is dropping them — but that setting is per-user, undocumented, and has no supported
 * way to read it back, so the vendor is all there is to key on.
 */
private val notificationTemplateDropsCallStyleActions: Boolean by lazy {
    // Redmi and POCO devices report Xiaomi as the manufacturer, so this one check covers the family.
    val drops = Build.MANUFACTURER.lowercase(Locale.ROOT) == MANUFACTURER_XIAOMI
    // The ROM version properties are logged but deliberately not part of the decision. The vendor
    // is the blunt signal: a Xiaomi device running a non-MIUI ROM renders CallStyle correctly and
    // would be better off on the CallStyle path. Narrowing to "is this MIUI" needs to know which
    // property HyperOS actually exposes, which these lines are here to establish from a real
    // device before the gate is tightened.
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

    /**
     * Keeps [isForegroundStarted] and [foregroundStarted] in lockstep; the flow is what the
     * notification collector waits on, the field is what the synchronous paths read.
     */
    private fun setForegroundStarted(value: Boolean) {
        isForegroundStarted = value
        foregroundStarted.value = value
    }

    /**
     * The notification currently posted. Re-posting paths that must not change what the user sees —
     * a foreground-service-type update after a permission grant — republish this instead of
     * rebuilding, which also keeps them free of injected state.
     */
    @Volatile
    private var currentNotification: Notification? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** The start id of the latest delivered start; a failed promotion stops only up to it. */
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

    /**
     * Renders the notification from call state, as decided by [callNotificationStates]. Every
     * input matters: the status selects the text, the connect instant is what the shell's
     * status-bar call chip counts from — for 1v1 the status reaches CONNECTED before the duration
     * timer stamps the instant, so collecting the status alone would publish a chip with no timer
     * and never correct it — the foreground flag gates posting until the service is actually
     * in the foreground, and the room id gates it on the hang-up action being routable. The
     * foreground flag has to be collected rather than read: the collector starts in [onCreate],
     * before the first [onStartCommand], and an unchanged call state would otherwise never be
     * re-rendered once the service does go foreground.
     *
     * Runs on the main thread so that building and posting is serialised against [onStartCommand],
     * which is itself a main-looper callback.
     */
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
                    // The service has already been told to stop; collapse our local state to match.
                    setForegroundStarted(false)
                }
            }
        }
    }

    /**
     * Every promotion goes through here. The notification is built by [build] on a channel verified
     * to exist at this very promotion (#1354); with none, the service stops without
     * `startForeground`, which on a missing channel would get the app killed. A type the platform
     * denies (a microphone or camera start without while-in-use eligibility throws
     * [SecurityException], which the shared helper does not catch) is retried as DATA_SYNC alone. A
     * start the platform refuses outright makes the helper return false, having stopped the service
     * itself. On any failure the owning session is told so it can retry from the foreground; a
     * denial or a missing channel stops the service up to [lastStartId], so a start already queued
     * behind it survives. Returns the notification now in the foreground, or null.
     */
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

    /** What is on screen when it already sits on [channelId], else the starting notification there. */
    private fun currentOrStarting(channelId: String): Notification =
        currentNotification?.takeIf { it.channelId == channelId } ?: buildStartingNotification(channelId)

    /** One promotion as [type]: the helper's result, or null when the platform denies the type. */
    private fun promoteOrNull(notification: Notification, type: Int): Boolean? = try {
        ForegroundServiceStarter.startForegroundSafely(this, DEFAULT_NOTIFICATION_ID, notification, type)
    } catch (e: SecurityException) {
        L.w { "[Call] ForegroundService type denied session=$ownerSessionId type=$type: ${e.message}" }
        null
    }

    /**
     * Brings the call UI back to the front. Every notification this service posts carries it:
     * an ongoing-call entry the user cannot tap their way back into is a dead end, and the
     * starting notification is what is on screen for the whole pre-room-id window.
     */
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

    /** The notification's hang-up action: an explicit-component broadcast to the non-exported receiver. */
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
            // The instant the call connected, or 0 while it is still connecting. The shell
            // counts its status-bar call chip up from this value, and a 0 leaves the chip
            // without a timer rather than showing a wrong one.
            .setWhen(connectedAt)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            // Re-posted on every call-state transition; without this each post re-alerts in the
            // middle of a live call.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true) // Full-screen intent

        if (notificationTemplateDropsCallStyleActions) {
            builder
                .addAction(
                    com.difft.android.call.R.drawable.call_ic_hangup,
                    getString(com.difft.android.call.R.string.call_notification_hang_up),
                    hangUpPendingIntent
                )
                .setStyle(NotificationCompat.InboxStyle())
                // CallStyle renders the elapsed time itself; a standard template has to be asked
                // for it. The system advances the stopwatch on its own, so the notification is
                // not re-posted to keep it moving. Suppressed while connecting, where `when` is
                // 0 and a stopwatch would count from the epoch.
                .setShowWhen(connectedAt > 0)
                .setUsesChronometer(connectedAt > 0)
        } else {
            val person = Person.Builder().setName(PackageUtil.getAppName()).build()
            builder.setStyle(NotificationCompat.CallStyle.forOngoingCall(person, hangUpPendingIntent))
        }

        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // Every startForegroundService() must be matched by startForeground() within ~5s (the one
        // exception: with no usable notification channel the service stops without promoting; starters
        // preflight, so that only happens on system restarts), even on
        // follow-up onStartCommand when already foreground. A follow-up re-posts what is already on
        // screen: rebuilding the starting notification there would drop a connected call back to
        // "Connecting…" without CallStyle. The first start has nothing posted yet, which is also
        // what keeps that path free of injected state. The state collector takes over from here.
        lastStartId = startId
        val isTypeUpdate = intent?.action == ACTION_UPDATE_SERVICE_TYPE
        // The update start has arrived, promoted or not: from here on a stop may bring the service down.
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
            // Handled on the main thread so its read-and-post of the current notification is
            // ordered against the notification collector, which runs there too — off-thread it
            // could republish a notification the collector has already superseded.
            L.i { "[Call] ForegroundService +++ Updating Service Type +++" }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                updateServiceType()
            }
        }

        return START_NOT_STICKY
    }

    /**
     * No session owns the service: a start that reached it after its owner's stop, or a type update
     * the stop had to wait for. The mandatory promotion has run; stop up to [startId] so that a start
     * already queued behind this one (a new session's) survives and re-checks.
     */
    private fun stopWithoutOwner(startId: Int) {
        L.i { "[Call] ForegroundService no owner, stopping startId=$startId" }
        // Clear first so a state change in this window cannot re-promote the service.
        setForegroundStarted(false)
        // Nothing of the ended call may be republished by a later start of the same instance.
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
        return null // not used.
    }

    /** A swiped-away task, the app's or the call page's, leaves the call running. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        L.i { "[Call] ForegroundService onTaskRemoved rootAction=${rootIntent?.action}" }
    }

    /**
     * The notification published for the initial foreground promotion. The service is started
     * before the call is up, so "Connecting…" is what is true at that point; the state collector
     * replaces it as soon as a call status arrives.
     */
    private fun buildStartingNotification(channelId: String): Notification {
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(com.difft.android.base.R.drawable.base_ic_notification_small)
            .setContentTitle(PackageUtil.getAppName())
            .setContentText(getString(com.difft.android.call.R.string.call_connecting_title))
            .setOngoing(true)
            // An outgoing call has no room id until the create-call response lands, and the
            // state-driven ongoing notification is gated on that id, so this variant is what the
            // shade holds for that whole window. Without a content intent it would be an entry
            // the user cannot tap their way back into the call from.
            .setContentIntent(callActivityPendingIntent())
            // No `when` here, unlike the ongoing-call variant: the shell's status-bar call chip is
            // driven off CallStyle notifications only, so this variant never has a chip whose timer
            // a stamp could feed. Keeping it out also leaves this builder free of injected state,
            // which is what lets the foreground-start path be exercised without standing up Hilt.
            .setPriority(NotificationCompat.PRIORITY_MIN)
            // Re-posted on every start; a re-post must never alert, whichever channel carries it.
            .setOnlyAlertOnce(true)
        return NotificationChannelSpecs.applyForegroundFallback(builder, channelId).build()
    }

    /**
     * Updates the foreground service type based on current permissions.
     * This is necessary when permissions are granted after the service has started.
     */
    @RequiresApi(30)
    private fun updateServiceType() {
        if (isForegroundStarted) {
            val newServiceType = getServiceType()
            // Re-post what is already on screen rather than rebuilding: a connected call keeps its
            // CallStyle notification and hang-up action across the permission-grant update.
            if (promote(newServiceType, ::currentOrStarting) != null) {
                L.i { "[Call] ForegroundService service type updated successfully" }
            } else {
                // The service has already been told to stop; collapse our local state to match.
                setForegroundStarted(false)
            }
        }
    }

    companion object {
        /** True from the first successful promotion until the service stops; written on main. */
        @Volatile
        internal var isServiceRunning = false

        /** The call session the service runs for, or [NO_OWNER]; written on main by [OngoingCallServiceController]. */
        @Volatile
        internal var ownerSessionId: Long = NO_OWNER

        /** Type-update starts issued and not yet delivered; main-confined. */
        internal var pendingTypeUpdates = 0

        internal const val NO_OWNER = -1L
        internal const val EXTRA_SESSION_ID = "call_session_id"
        const val DEFAULT_NOTIFICATION_ID = 3456
        private const val SERVICE_TAG = "ForegroundService"
        const val ACTION_UPDATE_SERVICE_TYPE = "ACTION_UPDATE_SERVICE_TYPE"
        private const val REQUEST_CODE_HANG_UP = 1
    }
}
