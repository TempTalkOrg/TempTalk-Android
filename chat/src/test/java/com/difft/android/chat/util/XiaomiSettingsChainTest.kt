package com.difft.android.chat.util

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.OemSettingsLauncher
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Xiaomi is the only skin the app opens directly; its two pages and the openable-page rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class XiaomiSettingsChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `auto-start opens only the security center manager and leaves the rest to the app-details fallback`() {
        assertEquals(
            listOf(
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            AutoStartPermissionHelper.xiaomiIntents().map { it.component?.className },
        )
    }

    @Test
    fun `the power policy page carries the package and label`() {
        val intent = BackgroundActivityPermissionHelper.powerKeeperIntents(context).single()
        assertEquals("com.miui.powerkeeper.ui.HiddenAppsConfigActivity", intent.component?.className)
        assertEquals(context.packageName, intent.getStringExtra("package_name"))
    }

    @Test
    fun `the power policy page counts as openable only when exported and unprotected`() {
        val intent = BackgroundActivityPermissionHelper.powerKeeperIntents(context).single()
        val pm = shadowOf(context.packageManager)
        fun register(exported: Boolean, permission: String?) = pm.addOrUpdateActivity(
            ActivityInfo().apply {
                name = "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                packageName = "com.miui.powerkeeper"
                this.exported = exported
                this.permission = permission
                applicationInfo = ApplicationInfo().apply { packageName = "com.miui.powerkeeper" }
            }
        )

        register(exported = true, permission = null)
        assertTrue(OemSettingsLauncher.canResolveAny(context, listOf(intent)))
        register(exported = false, permission = null)
        assertFalse(OemSettingsLauncher.canResolveAny(context, listOf(intent)))
        register(exported = true, permission = "com.miui.powerkeeper.PRIVATE")
        assertFalse(OemSettingsLauncher.canResolveAny(context, listOf(intent)))
    }

    @Test
    fun `the auto-start row is Xiaomi only and so is the power policy row`() {
        // The test device is not a Xiaomi, so neither row appears.
        assertFalse(AutoStartPermissionHelper.canOpenAutoStartSettings())
        assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context))
    }
}
