package com.difft.android.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.OemSettingsLauncher

/**
 * Entry to Xiaomi's per-app power policy (省电策略), the one vendor page the app opens directly.
 * The switch state cannot be read, so callers show "check" and never claim a state.
 */
object BackgroundActivityPermissionHelper {

    private const val TAG = "BackgroundActivityPermissionHelper"

    /**
     * Shown only when this Xiaomi device has the page and the app can really start it: newer
     * HyperOS builds dropped it or protect it, and a row that can only fall back to app details is noise.
     */
    fun canOpenBackgroundActivitySettings(context: Context): Boolean =
        OemBrand.current() == OemBrand.XIAOMI && OemSettingsLauncher.canResolveAny(context, powerKeeperIntents(context))

    fun openBackgroundActivitySettings(context: Context): Boolean =
        OemSettingsLauncher.openFirstAvailable(context, powerKeeperIntents(context), TAG)

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
