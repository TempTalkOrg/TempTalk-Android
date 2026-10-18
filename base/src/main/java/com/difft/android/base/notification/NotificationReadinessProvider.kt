package com.difft.android.base.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the system's notification and incoming-call state and evaluates it with
 * [NotificationReadinessEvaluator]. Reads cross process boundaries, so [read] always runs on the IO dispatcher.
 */
@Singleton
class NotificationReadinessProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun read(): NotificationReadiness = withContext(Dispatchers.IO) { readBlocking() }

    /** Must not be called on the main thread. */
    fun readBlocking(): NotificationReadiness = NotificationReadinessEvaluator.evaluate(readInput())

    internal fun readInput(): ReadinessInput {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val sdk = Build.VERSION.SDK_INT
        val brand = OemBrand.current()
        // Inline SDK_INT checks: lint NewApi does not accept a local copy as a version guard.
        val postGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        val groupBlocked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            nm.getNotificationChannelGroup(NotificationChannelIds.MESSAGE_GROUP)?.isBlocked == true
        return ReadinessInput(
            sdkInt = sdk,
            postNotificationsGranted = postGranted,
            appNotificationsEnabled = nm.areNotificationsEnabled(),
            messageGroupBlocked = groupBlocked,
            messageChannel = channel(nm, NotificationChannelIds.MESSAGE),
            callChannel = channel(nm, NotificationChannelIds.CALL),
            criticalChannel = channel(nm, NotificationChannelIds.CRITICAL_ALERT),
            canUseFullScreenIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) nm.canUseFullScreenIntent() else null,
            oemBrand = brand,
        )
    }

    private fun channel(nm: NotificationManager, id: String): ChannelReading? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return nm.getNotificationChannel(id)?.toReading()
    }

    private fun NotificationChannel.toReading() = ChannelReading(importance = importance, hasSound = sound != null, lockscreenVisibility = lockscreenVisibility)
}
