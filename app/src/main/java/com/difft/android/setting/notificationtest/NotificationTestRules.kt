package com.difft.android.setting.notificationtest

import android.app.NotificationManager
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus


enum class PrecheckBlock { MESSAGE_OFF, LOCK_HIDDEN, CALL_OFF, FULL_SCREEN_OFF, DND_ON }


internal data class Precheck(val block: PrecheckBlock, val fix: NotificationFix?)


internal enum class VoidReason(val logName: String) { UNLOCKED("unlocked"), NOT_LOCKED("not_locked"), DND("dnd") }


internal object NotificationTestRules {

    
    fun isDndOn(interruptionFilter: Int): Boolean = interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

    




    fun precheck(kind: NotificationTestKind, readiness: NotificationReadiness, dndOn: Boolean): Precheck? {
        val appOff = readiness.item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM
        return when (kind) {
            NotificationTestKind.BANNER, NotificationTestKind.LOCKSCREEN -> {
                val message = readiness.item(ReadinessItemId.N2)
                when {
                    appOff -> Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.APP_NOTIFICATIONS)
                    kind == NotificationTestKind.LOCKSCREEN && message?.issue == ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN ->
                        Precheck(PrecheckBlock.LOCK_HIDDEN, NotificationFix.MESSAGE_CATEGORY)
                    
                    message?.status == ReadinessStatus.PROBLEM && message.issue != ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET ->
                        Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.MESSAGE_CATEGORY)
                    dndOn -> Precheck(PrecheckBlock.DND_ON, null)
                    else -> null
                }
            }
            NotificationTestKind.CALL -> when {
                appOff -> Precheck(PrecheckBlock.CALL_OFF, NotificationFix.APP_NOTIFICATIONS)
                readiness.item(ReadinessItemId.C1)?.let { it.status == ReadinessStatus.PROBLEM && it.issue != ReadinessIssue.CALL_CATEGORY_NEVER_SET } == true ->
                    Precheck(PrecheckBlock.CALL_OFF, NotificationFix.CALL_CATEGORY)
                readiness.item(ReadinessItemId.C2)?.status == ReadinessStatus.PROBLEM ->
                    Precheck(PrecheckBlock.FULL_SCREEN_OFF, NotificationFix.FULL_SCREEN_INTENT)
                dndOn -> Precheck(PrecheckBlock.DND_ON, null)
                else -> null
            }
        }
    }

    
    const val LOCK_SETTLE_MS = 5_000L

    




    fun awaitingLock(keyguardLocked: Boolean, interactive: Boolean, sinceScreenOffMs: Long): Boolean =
        !keyguardLocked && !interactive && sinceScreenOffMs < LOCK_SETTLE_MS

    






    fun voidReason(kind: NotificationTestKind, keyguardLocked: Boolean, interactive: Boolean, dndOn: Boolean): VoidReason? = when {
        !keyguardLocked && interactive -> VoidReason.UNLOCKED
        dndOn -> VoidReason.DND
        kind == NotificationTestKind.LOCKSCREEN && !keyguardLocked -> VoidReason.NOT_LOCKED
        else -> null
    }
}
