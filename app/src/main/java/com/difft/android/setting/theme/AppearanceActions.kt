package com.difft.android.setting.theme

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.view.Display
import androidx.core.content.ContextCompat
import com.difft.android.base.ui.sidechrome.NavigationPlacement
import com.difft.android.base.ui.sidechrome.SideChrome
import com.difft.android.base.ui.sidechrome.SideChromeEligibility
import com.difft.android.base.utils.StatusBarSetting
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.density.DisplayDensityNormalizer
import com.difft.android.base.utils.density.DisplaySize








internal class AppearanceActions(private val activity: Activity) {
    private var leaving = false

    fun setLargeText(large: Boolean) {
        TextSizeUtil.updateTextSize(if (large) TextSizeUtil.TEXT_SIZE_LAGER else TextSizeUtil.TEXT_SIZE_DEFAULT)
    }

    
    fun setDisplaySize(size: DisplaySize): Boolean {
        if (leaving) return false
        DisplayDensityNormalizer.setDisplaySize(activity, size)
        leaving = activity in DisplayDensityNormalizer.recreateManagedActivities()
        return true
    }

    
    fun setNavigationPlacement(placement: NavigationPlacement): Boolean {
        if (leaving) return false
        leaving = activity in SideChrome.applyPlacement(activity, placement)
        return true
    }

    fun setStatusBarHidden(hidden: Boolean) {
        StatusBarSetting.setHidden(activity, hidden)
    }
}


internal object NavigationPlacementAvailability {

    



    fun isAvailable(context: Context): Boolean {
        val config = context.resources.configuration
        return SideChromeEligibility.shortScreen(
            hasHinge = context.packageManager.hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE),
            onDefaultDisplay = ContextCompat.getDisplayOrDefault(context).displayId == Display.DEFAULT_DISPLAY,
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
        )
    }
}
