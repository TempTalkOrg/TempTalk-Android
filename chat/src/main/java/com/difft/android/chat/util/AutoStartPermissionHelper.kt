package com.difft.android.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.OemSettingsLauncher
import com.difft.android.base.notification.VivoOs






object AutoStartPermissionHelper {

    private val TAG = L.tag(AutoStartPermissionHelper::class.java)

    



    fun canOpenAutoStartSettings(
        context: Context,
        brand: OemBrand = OemBrand.current(),
        originOsMajor: Int? = currentOriginMajor(brand),
    ): Boolean = when (brand) {
        OemBrand.XIAOMI -> true
        OemBrand.VIVO -> VivoOs.supportsDirectPermissionPage(originOsMajor) &&
            OemSettingsLauncher.canResolveAny(context, vivoIntents(context.packageName))
        else -> false
    }

    
    fun openAutoStartSettings(
        context: Context,
        brand: OemBrand = OemBrand.current(),
        originOsMajor: Int? = currentOriginMajor(brand),
    ): Boolean = try {
        val intents = if (brand == OemBrand.VIVO && VivoOs.supportsDirectPermissionPage(originOsMajor)) {
            vivoIntents(context.packageName)
        } else {
            xiaomiIntents()
        }
        OemSettingsLauncher.openFirstAvailable(context, intents, TAG)
    } catch (e: Exception) {
        L.e(e) { "$TAG Failed to open auto-start settings" }
        false
    }

    internal fun xiaomiIntents(): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
    )

    internal fun vivoIntents(pkg: String): List<Intent> = listOf(VivoOs.permissionDetailIntent(pkg))

    private fun currentOriginMajor(brand: OemBrand): Int? = if (brand == OemBrand.VIVO) VivoOs.currentOriginMajor() else null
}
