package com.difft.android.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.OemSettingsLauncher
import com.difft.android.base.notification.VivoOs

/**
 * Entry to the vendor per-app background power policy that the app opens directly: Xiaomi's power
 * policy (省电策略) and, on vivo OriginOS 6+, the app's power-detail page, whose 「后台耗电管理」 row
 * leads to the per-app choice; the system-wide 「后台耗电管理」 app list is the fallback when that
 * page is missing. The switch state cannot be read, so callers show "check" and never claim a state.
 */
object BackgroundActivityPermissionHelper {

    private const val TAG = "BackgroundActivityPermissionHelper"
    private const val VIVO_POWER_PACKAGE = "com.iqoo.powersaving"

    /**
     * Shown only when this device has the page and the app can really start it: newer
     * HyperOS builds dropped it or protect it, and a row that can only fall back to app details is noise.
     */
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

    /** Per-app details first (extra key must be exactly `package_name`), the app list second. */
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
