package com.difft.android.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.OemSettingsLauncher
import com.difft.android.base.notification.VivoOs







object BackgroundActivityPermissionHelper {

    private const val TAG = "BackgroundActivityPermissionHelper"
    private const val VIVO_POWER_PACKAGE = "com.iqoo.powersaving"

    



    fun canOpenBackgroundActivitySettings(
        context: Context,
        brand: OemBrand = OemBrand.current(),
        originOsMajor: Int? = if (brand == OemBrand.VIVO) VivoOs.currentOriginMajor() else null,
    ): Boolean = directIntents(context, brand, originOsMajor).let { it.isNotEmpty() && OemSettingsLauncher.canResolveAny(context, it) }

    fun openBackgroundActivitySettings(
        context: Context,
        brand: OemBrand = OemBrand.current(),
        originOsMajor: Int? = if (brand == OemBrand.VIVO) VivoOs.currentOriginMajor() else null,
    ): Boolean =
        OemSettingsLauncher.openFirstAvailable(context, directIntents(context, brand, originOsMajor), TAG)

    private fun directIntents(context: Context, brand: OemBrand, originOsMajor: Int?): List<Intent> = when {
        brand == OemBrand.XIAOMI -> powerKeeperIntents(context)
        brand == OemBrand.VIVO && VivoOs.supportsDirectPermissionPage(originOsMajor) -> vivoPowerIntents(context.packageName)
        else -> emptyList()
    }

    
    internal fun vivoPowerIntents(pkg: String): List<Intent> = listOf(
        Intent("com.iqoo.powersaving.PowerUsageSummaryActivity.search")
            .setPackage(VIVO_POWER_PACKAGE)
            .putExtra("package_name", pkg),
        Intent("com.iqoo.powersaving.battery.high.power.jump").setPackage(VIVO_POWER_PACKAGE),
    )

    internal fun powerKeeperIntents(context: Context): List<Intent> {
        val pkg = context.packageName
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault(pkg)
        return listOf(
            Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", pkg)
                .putExtra("package_label", label),
        )
    }
}
