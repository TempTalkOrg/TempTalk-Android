package com.difft.android.setting

import androidx.annotation.StringRes
import com.difft.android.base.notification.OemBrand
import com.difft.android.chat.R as ChatR


internal enum class BgRow {
    BACKGROUND_RESTRICTION,
    BATTERY_OPTIMIZATION,
    DATA_SAVER,
    EXACT_ALARM,

    
    AUTO_START,

    
    BACKGROUND_ACTIVITY,

    
    BACKGROUND_POWER,

    
    VENDOR_GUIDE,
}


internal data class BgRowFlags(
    val showBackgroundRestriction: Boolean,
    val canOpenAutoStart: Boolean,
    val canOpenBackgroundActivity: Boolean,
    val exactAlarmApplicable: Boolean,
)


internal fun isVivoDirectRows(brand: OemBrand, canOpenAutoStart: Boolean, canOpenBackgroundActivity: Boolean): Boolean =
    brand == OemBrand.VIVO && canOpenAutoStart && canOpenBackgroundActivity






internal fun showVendorGuide(brand: OemBrand, vivoDirect: Boolean): Boolean = when (brand) {
    OemBrand.HUAWEI, OemBrand.HONOR, OemBrand.OPPO, OemBrand.MEIZU, OemBrand.OTHER_VENDOR -> true
    OemBrand.VIVO -> !vivoDirect
    else -> false
}

private fun vendorRows(brand: OemBrand, flags: BgRowFlags): List<BgRow> = buildList {
    when (brand) {
        OemBrand.XIAOMI -> {
            if (flags.canOpenAutoStart) add(BgRow.AUTO_START)
            if (flags.canOpenBackgroundActivity) add(BgRow.BACKGROUND_ACTIVITY)
        }
        OemBrand.VIVO -> {
            if (flags.canOpenAutoStart) add(BgRow.AUTO_START)
            if (flags.canOpenBackgroundActivity) add(BgRow.BACKGROUND_POWER)
        }
        else -> Unit
    }
    if (showVendorGuide(brand, isVivoDirectRows(brand, flags.canOpenAutoStart, flags.canOpenBackgroundActivity))) {
        add(BgRow.VENDOR_GUIDE)
    }
}


internal val GENERIC_ROWS = setOf(BgRow.BACKGROUND_RESTRICTION, BgRow.BATTERY_OPTIMIZATION, BgRow.DATA_SAVER, BgRow.EXACT_ALARM)


internal fun backgroundRowOrder(brand: OemBrand, flags: BgRowFlags): List<BgRow> {
    val generic = buildList {
        if (flags.showBackgroundRestriction) add(BgRow.BACKGROUND_RESTRICTION)
        add(BgRow.BATTERY_OPTIMIZATION)
        add(BgRow.DATA_SAVER)
        if (flags.exactAlarmApplicable) add(BgRow.EXACT_ALARM)
    }
    val vendor = vendorRows(brand, flags)
    return generic + vendor
}


internal data class BgHint(@param:StringRes val res: Int, val takesAppName: Boolean = false)


internal fun hintFor(row: BgRow, brand: OemBrand): BgHint? = when (row) {
    BgRow.AUTO_START -> when (brand) {
        OemBrand.VIVO -> BgHint(ChatR.string.background_hint_auto_start_vivo)
        OemBrand.XIAOMI -> BgHint(ChatR.string.background_hint_auto_start_xiaomi, takesAppName = true)
        else -> null
    }
    BgRow.BACKGROUND_ACTIVITY -> BgHint(ChatR.string.background_hint_power_policy_xiaomi)
    BgRow.BACKGROUND_POWER -> BgHint(ChatR.string.background_power_hint_vivo)
    BgRow.VENDOR_GUIDE -> BgHint(ChatR.string.background_hint_vendor_guide)
    else -> null
}

@StringRes
internal fun titleFor(row: BgRow): Int = when (row) {
    BgRow.BACKGROUND_RESTRICTION -> ChatR.string.background_restriction_check
    BgRow.BATTERY_OPTIMIZATION -> ChatR.string.battery_optimization_check
    BgRow.DATA_SAVER -> ChatR.string.data_saver_check
    BgRow.EXACT_ALARM -> ChatR.string.exact_alarm_check
    BgRow.AUTO_START -> ChatR.string.auto_start_check
    BgRow.BACKGROUND_ACTIVITY -> ChatR.string.background_activity_check
    BgRow.BACKGROUND_POWER -> ChatR.string.background_power_check
    BgRow.VENDOR_GUIDE -> ChatR.string.background_vendor_settings
}


internal data class BgCheckState(
    val isBackgroundRestricted: Boolean,
    val isIgnoringBatteryOpt: Boolean,
    val isDataSaverRestricted: Boolean,
    val exactAlarmState: ExactAlarmState = ExactAlarmState.NotApplicable,
)


internal data class BgStatus(@param:StringRes val res: Int?, val warning: Boolean)

internal fun statusOf(row: BgRow, state: BgCheckState): BgStatus = when (row) {
    BgRow.BACKGROUND_RESTRICTION ->
        if (state.isBackgroundRestricted) {
            BgStatus(ChatR.string.background_restriction_restricted, warning = true)
        } else {
            BgStatus(ChatR.string.background_restriction_unrestricted, warning = false)
        }
    BgRow.BATTERY_OPTIMIZATION ->
        if (state.isIgnoringBatteryOpt) {
            BgStatus(ChatR.string.battery_optimization_ignored, warning = false)
        } else {
            BgStatus(ChatR.string.battery_optimization_not_ignored, warning = true)
        }
    BgRow.DATA_SAVER ->
        if (state.isDataSaverRestricted) {
            BgStatus(ChatR.string.data_saver_restricted, warning = true)
        } else {
            BgStatus(ChatR.string.data_saver_unrestricted, warning = false)
        }
    BgRow.EXACT_ALARM ->
        if (state.exactAlarmState == ExactAlarmState.Granted) {
            BgStatus(ChatR.string.exact_alarm_granted, warning = false)
        } else {
            BgStatus(ChatR.string.exact_alarm_not_granted, warning = true)
        }
    
    else -> BgStatus(null, warning = false)
}


internal enum class BgRequirement(val row: BgRow) {
    BACKGROUND_RESTRICTION(BgRow.BACKGROUND_RESTRICTION),
    BATTERY_OPTIMIZATION(BgRow.BATTERY_OPTIMIZATION),
    DATA_SAVER(BgRow.DATA_SAVER),
}


internal fun firstFailingRequirement(state: BgCheckState): BgRequirement? = when {
    state.isBackgroundRestricted -> BgRequirement.BACKGROUND_RESTRICTION
    !state.isIgnoringBatteryOpt -> BgRequirement.BATTERY_OPTIMIZATION
    state.isDataSaverRestricted -> BgRequirement.DATA_SAVER
    else -> null
}


internal sealed interface ReturnAction {
    data object None : ReturnAction
    data object AutoEnable : ReturnAction
    data class Highlight(val requirement: BgRequirement) : ReturnAction
}





internal fun returnAction(startedFromSwitch: Boolean, alreadyOn: Boolean, failing: BgRequirement?): ReturnAction = when {
    !startedFromSwitch || alreadyOn -> ReturnAction.None
    failing == null -> ReturnAction.AutoEnable
    else -> ReturnAction.Highlight(failing)
}
