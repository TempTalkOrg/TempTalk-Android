package com.difft.android.chat.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.OemSettingsLauncher

/**
 * Entry to Xiaomi's auto-start manager, the one vendor page the app opens directly. Other skins keep
 * these switches in places that change between versions, so their guidance lives on the setup guide.
 */
object AutoStartPermissionHelper {

    private val TAG = L.tag(AutoStartPermissionHelper::class.java)

    /** Shown on Xiaomi only; the state of the switch is not readable, so the row says "check". */
    fun canOpenAutoStartSettings(): Boolean = OemBrand.current() == OemBrand.XIAOMI

    /** Opens the auto-start manager, falling back to the system app-details page. */
    fun openAutoStartSettings(context: Context): Boolean = try {
        OemSettingsLauncher.openFirstAvailable(context, xiaomiIntents(), TAG)
    } catch (e: Exception) {
        L.e(e) { "$TAG Failed to open auto-start settings" }
        false
    }

    internal fun xiaomiIntents(): List<Intent> = listOf(
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        ),
    )
}
