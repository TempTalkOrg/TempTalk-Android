package com.difft.android.setting

import com.difft.android.base.notification.CategoryLevel
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessItem
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.chat.util.CriticalAlertBypass

/** The words a status value can show. */
internal enum class StatusLabel { ON, OFF, NO_BANNER, LOCK_HIDDEN, SILENT, NEEDS_CHECK, DND_BLOCKED, NEEDS_FULL_SCREEN, NEEDS_CONNECTION }

/**
 * One row's status: a [label] only when the system reports it (null shows nothing) and [warning]
 * only for a definite problem.
 */
internal data class SettingStatus(val label: StatusLabel?, val warning: Boolean = false) {
    companion object {
        val NONE = SettingStatus(null)
        fun on() = SettingStatus(StatusLabel.ON)
        fun problem(label: StatusLabel) = SettingStatus(label, warning = true)
    }
}

/** Skins whose app notification overview carries app-level switches the app cannot read. */
private val OVERVIEW_HINT_BRANDS = setOf(OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.OPPO)

/**
 * Row visibility and status/jump mapping of the notification settings page and its Calls and
 * Critical Alerts pages, kept free of Android types so the rules are unit-testable. A status shows
 * "on" only when it can be read and otherwise only the first definite problem. The vendor pop-up
 * switch (C3) and notification test results feed no status: neither can be read back, and a test
 * result is not invalidated by a later vendor switch change.
 */
internal class NotificationSettingsUiState(
    readiness: NotificationReadiness,
    sdkInt: Int,
    val dndAccessGranted: Boolean,
    bypass: CriticalAlertBypass,
    backgroundConnectionRunning: Boolean = true,
    oemPopupDirect: Boolean = false,
) {
    val oemBrand: OemBrand = readiness.oemBrand

    private val appNotificationsStatus: ReadinessStatus? = readiness.item(ReadinessItemId.N1)?.status

    /** The top banner appears only when the app-level notification switch is proven off. */
    val showBanner: Boolean = appNotificationsStatus == ReadinessStatus.PROBLEM

    /** The App notifications group repeats what the banner says, so it is hidden while the banner shows. */
    val showAppNotificationsGroup: Boolean = !showBanner

    /**
     * The App notifications row says on only when the switch reads on and nothing unreadable sits on
     * that page: on Xiaomi, vivo and OPPO the page also holds app-level switches (floating, lock
     * screen, silence) the app cannot read, so the row shows no status there.
     */
    val appNotificationsOn: Boolean = appNotificationsStatus == ReadinessStatus.OK && oemBrand !in OVERVIEW_HINT_BRANDS

    val messageLevel: CategoryLevel = readiness.messageLevel

    val callLevel: CategoryLevel = readiness.callLevel

    /** Full-screen intent is a runtime setting only on API 34+. */
    val fullScreenItem: ReadinessItem? =
        if (sdkInt >= API_U) readiness.item(ReadinessItemId.C2) else null

    /** The vendor switch exists only on China OEM skins (the evaluator omits the item elsewhere); its row shows no status. */
    val oemPopupItem: ReadinessItem? = readiness.item(ReadinessItemId.C3)

    /**
     * [oemPopupDirect] marks a vendor page the row opens directly: Xiaomi's permission editor always,
     * vivo's permission page on OriginOS 6+ when it resolves. Every other vendor row opens the setup guide.
     */
    val oemPopupOpensGuide: Boolean = oemPopupItem != null && !(oemBrand == OemBrand.XIAOMI || oemPopupDirect)

    /** Xiaomi keeps the caption naming its permission-editor switches. */
    val showXiaomiPopupHint: Boolean = oemPopupItem != null && oemBrand == OemBrand.XIAOMI

    /**
     * Xiaomi, vivo and OPPO keep app-level switches (floating, lock screen, silence) on the app's
     * notification overview that the app cannot read and that every category depends on, so the
     * caption naming them stays under App notifications; it hides with that group.
     */
    val showOverviewHint: Boolean = oemBrand in OVERVIEW_HINT_BRANDS && showAppNotificationsGroup

    /** vivo keeps a caption naming the switches on its permission page. */
    val showVivoPopupHint: Boolean = oemPopupItem != null && oemBrand == OemBrand.VIVO && oemPopupDirect

    /**
     * Xiaomi's lock-screen choice for a category reads the same whether it shows or hides content, so
     * the Calls and Critical Alerts category rows ask the user to check it in the category settings.
     */
    val showXiaomiLockScreenNote: Boolean = oemBrand == OemBrand.XIAOMI

    /** The "Background connection" row shows On or Off. */
    val backgroundConnectionOn: Boolean = backgroundConnectionRunning

    /** On the Critical Alerts page an off connection is a problem only once the feature is on. */
    val backgroundConnectionWarning: Boolean = dndAccessGranted && !backgroundConnectionRunning

    /** Without access the row opens the app's access page; with access, the access list to review or revoke it. */
    val dndFix: NotificationFix = if (dndAccessGranted) NotificationFix.DND_ACCESS_LIST else NotificationFix.DND_ACCESS

    private val fullScreenDenied: Boolean = fullScreenItem?.status == ReadinessStatus.PROBLEM

    /** Top-level Calls row: app notifications, the call category, then full-screen display. */
    val callSummary: SettingStatus = summarizeCall(callLevel, fullScreenDenied)

    /**
     * The Critical Alerts category row: app notifications off silences it; then the category, then the
     * Do Not Disturb bypass. On is shown only when every part reads on, including vendor switches.
     */
    val criticalCategoryStatus: SettingStatus = readCriticalCategory(
        appNotificationsOff = showBanner,
        a1 = readiness.item(ReadinessItemId.A1),
        bypass = bypass,
        channelsExist = sdkInt >= API_O,
        vendorSwitchesReadable = oemBrand.vendorAlertSwitchesReadable,
    )

    /**
     * Top-level Critical Alerts row. Do Not Disturb access is what turns the feature on and it reads
     * exactly, so the row says Off without it (no warning: the feature is not in use) and On with it;
     * every definite problem of a dependency, in order, replaces On: the category, full-screen display,
     * the connection.
     */
    val criticalSummary: SettingStatus = when {
        !dndAccessGranted -> SettingStatus(StatusLabel.OFF)
        criticalCategoryStatus.warning -> criticalCategoryStatus
        fullScreenDenied -> SettingStatus.problem(StatusLabel.NEEDS_FULL_SCREEN)
        !backgroundConnectionRunning -> SettingStatus.problem(StatusLabel.NEEDS_CONNECTION)
        // A missing or unreadable category may not post at all, so it is no "on".
        readiness.item(ReadinessItemId.A1)?.status != ReadinessStatus.OK -> SettingStatus.NONE
        else -> SettingStatus.on()
    }

    private companion object {
        const val API_O = 26
        const val API_U = 34

        fun categoryProblem(level: CategoryLevel): StatusLabel? = when (level) {
            CategoryLevel.OFF -> StatusLabel.OFF
            CategoryLevel.NO_BANNER -> StatusLabel.NO_BANNER
            CategoryLevel.LOCK_HIDDEN -> StatusLabel.LOCK_HIDDEN
            CategoryLevel.SILENT -> StatusLabel.SILENT
            CategoryLevel.NEEDS_CHECK -> StatusLabel.NEEDS_CHECK
            CategoryLevel.ON, CategoryLevel.UNKNOWN, CategoryLevel.CONFIRMED, CategoryLevel.TESTED_OK -> null
        }

        /** [callLevel] already reads Off while app notifications are off. */
        fun summarizeCall(callLevel: CategoryLevel, fullScreenDenied: Boolean): SettingStatus {
            categoryProblem(callLevel)?.let { return SettingStatus.problem(it) }
            return when {
                fullScreenDenied -> SettingStatus.problem(StatusLabel.NEEDS_FULL_SCREEN)
                // Only a reading on a brand with readable vendor switches; a passed test is not enough.
                callLevel == CategoryLevel.ON -> SettingStatus.on()
                else -> SettingStatus.NONE
            }
        }

        fun readCriticalCategory(
            appNotificationsOff: Boolean,
            a1: ReadinessItem?,
            bypass: CriticalAlertBypass,
            channelsExist: Boolean,
            vendorSwitchesReadable: Boolean,
        ): SettingStatus = when {
            appNotificationsOff -> SettingStatus.problem(StatusLabel.OFF)
            a1?.issue == ReadinessIssue.CRITICAL_CATEGORY_OFF -> SettingStatus.problem(StatusLabel.OFF)
            a1?.issue == ReadinessIssue.CRITICAL_CATEGORY_LOCK_HIDDEN -> SettingStatus.problem(StatusLabel.LOCK_HIDDEN)
            a1?.issue == ReadinessIssue.CRITICAL_CATEGORY_NEVER_SET -> SettingStatus.problem(StatusLabel.NEEDS_CHECK)
            a1?.status == ReadinessStatus.PROBLEM -> SettingStatus.problem(StatusLabel.NO_BANNER)
            bypass == CriticalAlertBypass.NEEDS_USER_ACTION -> SettingStatus.problem(StatusLabel.DND_BLOCKED)
            // Channels exist from API 26; on needs the bypass read back, not a failed reconcile.
            a1?.status == ReadinessStatus.OK && (!channelsExist || bypass == CriticalAlertBypass.ENABLED) &&
                vendorSwitchesReadable -> SettingStatus.on()
            else -> SettingStatus.NONE
        }
    }
}
