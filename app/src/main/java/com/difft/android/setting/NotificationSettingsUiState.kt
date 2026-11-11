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


internal enum class StatusLabel { ON, OFF, NO_BANNER, LOCK_HIDDEN, SILENT, NEEDS_CHECK, DND_BLOCKED, NEEDS_FULL_SCREEN, NEEDS_CONNECTION }





internal data class SettingStatus(val label: StatusLabel?, val warning: Boolean = false) {
    companion object {
        val NONE = SettingStatus(null)
        fun on() = SettingStatus(StatusLabel.ON)
        fun problem(label: StatusLabel) = SettingStatus(label, warning = true)
    }
}


private val OVERVIEW_HINT_BRANDS = setOf(OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.OPPO)








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

    
    val showBanner: Boolean = appNotificationsStatus == ReadinessStatus.PROBLEM

    
    val showAppNotificationsGroup: Boolean = !showBanner

    




    val appNotificationsOn: Boolean = appNotificationsStatus == ReadinessStatus.OK && oemBrand !in OVERVIEW_HINT_BRANDS

    val messageLevel: CategoryLevel = readiness.messageLevel

    val callLevel: CategoryLevel = readiness.callLevel

    
    val fullScreenItem: ReadinessItem? =
        if (sdkInt >= API_U) readiness.item(ReadinessItemId.C2) else null

    
    val oemPopupItem: ReadinessItem? = readiness.item(ReadinessItemId.C3)

    



    val oemPopupOpensGuide: Boolean = oemPopupItem != null && !(oemBrand == OemBrand.XIAOMI || oemPopupDirect)

    
    val showXiaomiPopupHint: Boolean = oemPopupItem != null && oemBrand == OemBrand.XIAOMI

    




    val showOverviewHint: Boolean = oemBrand in OVERVIEW_HINT_BRANDS && showAppNotificationsGroup

    
    val showVivoPopupHint: Boolean = oemPopupItem != null && oemBrand == OemBrand.VIVO && oemPopupDirect

    



    val showXiaomiLockScreenNote: Boolean = oemBrand == OemBrand.XIAOMI

    
    val backgroundConnectionOn: Boolean = backgroundConnectionRunning

    
    val backgroundConnectionWarning: Boolean = dndAccessGranted && !backgroundConnectionRunning

    
    val dndFix: NotificationFix = if (dndAccessGranted) NotificationFix.DND_ACCESS_LIST else NotificationFix.DND_ACCESS

    private val fullScreenDenied: Boolean = fullScreenItem?.status == ReadinessStatus.PROBLEM

    
    val callSummary: SettingStatus = summarizeCall(callLevel, fullScreenDenied)

    



    val criticalCategoryStatus: SettingStatus = readCriticalCategory(
        appNotificationsOff = showBanner,
        a1 = readiness.item(ReadinessItemId.A1),
        bypass = bypass,
        channelsExist = sdkInt >= API_O,
        vendorSwitchesReadable = oemBrand.vendorAlertSwitchesReadable,
    )

    





    val criticalSummary: SettingStatus = when {
        !dndAccessGranted -> SettingStatus(StatusLabel.OFF)
        criticalCategoryStatus.warning -> criticalCategoryStatus
        fullScreenDenied -> SettingStatus.problem(StatusLabel.NEEDS_FULL_SCREEN)
        !backgroundConnectionRunning -> SettingStatus.problem(StatusLabel.NEEDS_CONNECTION)
        
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

        
        fun summarizeCall(callLevel: CategoryLevel, fullScreenDenied: Boolean): SettingStatus {
            categoryProblem(callLevel)?.let { return SettingStatus.problem(it) }
            return when {
                fullScreenDenied -> SettingStatus.problem(StatusLabel.NEEDS_FULL_SCREEN)
                
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
            
            a1?.status == ReadinessStatus.OK && (!channelsExist || bypass == CriticalAlertBypass.ENABLED) &&
                vendorSwitchesReadable -> SettingStatus.on()
            else -> SettingStatus.NONE
        }
    }
}
