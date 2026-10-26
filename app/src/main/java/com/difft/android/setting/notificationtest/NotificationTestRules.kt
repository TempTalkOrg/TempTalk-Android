package com.difft.android.setting.notificationtest

import android.app.NotificationManager
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus

/** Why a test does not start: a readable setting already explains the outcome, or would distort it. */
enum class PrecheckBlock { MESSAGE_OFF, LOCK_HIDDEN, CALL_OFF, FULL_SCREEN_OFF, DND_ON }

/** A blocked start and the settings page that fixes it; Do Not Disturb has no page to open. */
internal data class Precheck(val block: PrecheckBlock, val fix: NotificationFix?)

/** Why a running test is dropped without recording anything. */
internal enum class VoidReason(val logName: String) { UNLOCKED("unlocked"), NOT_LOCKED("not_locked"), DND("dnd") }

/** Pure decisions of the notification test page (#1350), free of Android state so they are unit-testable. */
internal object NotificationTestRules {

    /** Do Not Disturb counts as on only for a filter that silences something; unknown is treated as off. */
    fun isDndOn(interruptionFilter: Int): Boolean = interruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY ||
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALARMS ||
        interruptionFilter == NotificationManager.INTERRUPTION_FILTER_NONE

    /**
     * Null when [kind] may start. A standard reading that is a definite problem wins, because the
     * test could only confirm what the reading already shows; Do Not Disturb would suppress the alert
     * and be mistaken for a vendor switch.
     */
    fun precheck(kind: NotificationTestKind, readiness: NotificationReadiness, dndOn: Boolean): Precheck? {
        val appOff = readiness.item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM
        return when (kind) {
            NotificationTestKind.BANNER, NotificationTestKind.LOCKSCREEN -> {
                val message = readiness.item(ReadinessItemId.N2)
                when {
                    appOff -> Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.APP_NOTIFICATIONS)
                    kind == NotificationTestKind.LOCKSCREEN && message?.issue == ReadinessIssue.MESSAGE_CATEGORY_LOCK_HIDDEN ->
                        Precheck(PrecheckBlock.LOCK_HIDDEN, NotificationFix.MESSAGE_CATEGORY)
                    message?.status == ReadinessStatus.PROBLEM -> Precheck(PrecheckBlock.MESSAGE_OFF, NotificationFix.MESSAGE_CATEGORY)
                    dndOn -> Precheck(PrecheckBlock.DND_ON, null)
                    else -> null
                }
            }
            NotificationTestKind.CALL -> when {
                appOff -> Precheck(PrecheckBlock.CALL_OFF, NotificationFix.APP_NOTIFICATIONS)
                readiness.item(ReadinessItemId.C1)?.status == ReadinessStatus.PROBLEM ->
                    Precheck(PrecheckBlock.CALL_OFF, NotificationFix.CALL_CATEGORY)
                readiness.item(ReadinessItemId.C2)?.status == ReadinessStatus.PROBLEM ->
                    Precheck(PrecheckBlock.FULL_SCREEN_OFF, NotificationFix.FULL_SCREEN_INTENT)
                dndOn -> Precheck(PrecheckBlock.DND_ON, null)
                else -> null
            }
        }
    }

    /** How long after the screen goes off a lock-screen or call test waits for the keyguard to lock. */
    const val LOCK_SETTLE_MS = 5_000L

    /**
     * Whether to keep waiting for the keyguard before posting. A lock delay, or no secure lock at
     * all, leaves it unlocked for a while after the screen goes off; the wait ends once it locks,
     * the screen comes back on, or [LOCK_SETTLE_MS] has passed since the screen went off.
     */
    fun awaitingLock(keyguardLocked: Boolean, interactive: Boolean, sinceScreenOffMs: Long): Boolean =
        !keyguardLocked && !interactive && sinceScreenOffMs < LOCK_SETTLE_MS

    /**
     * Whether a lock-screen or call test may still count. A device unlocked and in use again voids
     * the run, as does Do Not Disturb. The call test also counts while the screen stays off on a
     * device whose keyguard has not locked (no secure lock or a lock delay): a full-screen intent
     * still launches then. The lock-screen test needs a locked keyguard, or there is no lock screen
     * to judge. Checked right before posting and again when the call test times out. Null: it counts.
     */
    fun voidReason(kind: NotificationTestKind, keyguardLocked: Boolean, interactive: Boolean, dndOn: Boolean): VoidReason? = when {
        !keyguardLocked && interactive -> VoidReason.UNLOCKED
        dndOn -> VoidReason.DND
        kind == NotificationTestKind.LOCKSCREEN && !keyguardLocked -> VoidReason.NOT_LOCKED
        else -> null
    }
}
