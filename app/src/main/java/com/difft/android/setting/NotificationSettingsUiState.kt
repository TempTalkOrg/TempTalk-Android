package com.difft.android.setting

import com.difft.android.base.notification.CategoryLevel
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessItem
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.chat.util.CriticalAlertBypass

/** What the Critical Alerts row shows. */
internal enum class CriticalAlertStatus { ON, OFF, NEEDS_ATTENTION, CHECK }

/**
 * Row visibility and status/jump mapping of the notification settings page, kept free of Android
 * types so the rules are unit-testable. Every status row has exactly one status and one jump.
 */
internal class NotificationSettingsUiState(
    readiness: NotificationReadiness,
    sdkInt: Int,
    dndAccessGranted: Boolean,
    bypass: CriticalAlertBypass,
    fcmUnavailable: Boolean = false,
    backgroundConnectionRunning: Boolean = true,
) {
    val oemBrand: OemBrand = readiness.oemBrand

    /** The top banner appears only when the app-level notification switch is proven off. */
    val showBanner: Boolean = readiness.item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM

    val messageLevel: CategoryLevel = readiness.messageLevel

    val callLevel: CategoryLevel = readiness.callLevel

    /** Full-screen intent is a runtime setting only on API 34+. */
    val fullScreenItem: ReadinessItem? =
        if (sdkInt >= API_U) readiness.item(ReadinessItemId.C2) else null

    /** The vendor switch exists only on China OEM skins; the evaluator omits the item elsewhere. */
    val oemPopupItem: ReadinessItem? = readiness.item(ReadinessItemId.C3)

    /** Xiaomi opens its permission editor; every other vendor row opens the setup guide. */
    val oemPopupOpensGuide: Boolean = oemPopupItem != null && oemBrand != OemBrand.XIAOMI

    /** The group holds only these two rows, so it disappears when both are absent. */
    val showLockScreenGroup: Boolean = fullScreenItem != null || oemPopupItem != null

    /**
     * Status from the row's own items only: Do Not Disturb access, the category and the read-back
     * bypass. Delivery (push or background connection) never changes it.
     */
    val criticalStatus: CriticalAlertStatus

    /**
     * Without access the row opens the app's access page; with everything verified it opens the
     * access list (to review or turn access off); anything unmet opens the category page.
     */
    val criticalFix: NotificationFix

    /** The access explanation is only for missing Do Not Disturb access, not for other Off causes. */
    val showDndAccessCaption: Boolean = !dndAccessGranted

    /** Push is known to be unavailable and nothing else keeps the connection: alerts cannot be delivered. */
    val showCriticalDeliveryCaption: Boolean = fcmUnavailable && !backgroundConnectionRunning

    init {
        val a1 = readiness.item(ReadinessItemId.A1)?.status
        criticalStatus = when {
            !dndAccessGranted -> CriticalAlertStatus.OFF
            // App notifications off silences the critical category too.
            readiness.item(ReadinessItemId.N1)?.status == ReadinessStatus.PROBLEM -> CriticalAlertStatus.OFF
            a1 == ReadinessStatus.PROBLEM || bypass == CriticalAlertBypass.NEEDS_USER_ACTION -> CriticalAlertStatus.NEEDS_ATTENTION
            a1 != ReadinessStatus.OK -> CriticalAlertStatus.CHECK
            // Channels exist from API 26; ON needs the bypass read back, not a failed reconcile.
            sdkInt >= API_O && bypass != CriticalAlertBypass.ENABLED -> CriticalAlertStatus.CHECK
            else -> CriticalAlertStatus.ON
        }
        criticalFix = when {
            !dndAccessGranted -> NotificationFix.DND_ACCESS
            criticalStatus == CriticalAlertStatus.ON -> NotificationFix.DND_ACCESS_LIST
            else -> NotificationFix.CRITICAL_ALERT_CATEGORY
        }
    }

    private companion object {
        const val API_O = 26
        const val API_U = 34
    }
}
