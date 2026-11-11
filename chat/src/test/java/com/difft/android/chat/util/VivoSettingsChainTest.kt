package com.difft.android.chat.util

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.OemBrand
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VivoSettingsChainTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg = context.packageName

    private fun register(intent: Intent, exported: Boolean = true, permission: String? = null) {
        shadowOf(context.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = "Target_${intent.action}"
                packageName = intent.`package`
                this.exported = exported
                this.permission = permission
                applicationInfo = ApplicationInfo().apply { packageName = intent.`package` }
            }
        )
        shadowOf(context.packageManager).addIntentFilterForActivity(
            android.content.ComponentName(intent.`package`!!, "Target_${intent.action}"),
            android.content.IntentFilter(intent.action),
        )
    }

    @Test
    fun `auto-start opens the vivo permission page with the exact extra key`() {
        val intent = AutoStartPermissionHelper.vivoIntents(pkg).single()
        assertEquals("permission.intent.action.softPermissionDetail", intent.action)
        assertEquals("com.vivo.permissionmanager", intent.`package`)
        assertEquals(pkg, intent.getStringExtra("packagename"))
    }

    @Test
    fun `background power tries the per-app page first and the app list second`() {
        val (perApp, list) = BackgroundActivityPermissionHelper.vivoPowerIntents(pkg)
        assertEquals("com.iqoo.powersaving.PowerUsageSummaryActivity.search", perApp.action)
        assertEquals("com.iqoo.powersaving", perApp.`package`)
        assertEquals(pkg, perApp.getStringExtra("package_name"))
        assertEquals("com.iqoo.powersaving.battery.high.power.jump", list.action)
        assertEquals("com.iqoo.powersaving", list.`package`)
    }

    @Test
    fun `vivo rows need OriginOS 6 and a startable page`() {
        
        assertFalse(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.VIVO, 6))
        assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.VIVO, 6))

        register(AutoStartPermissionHelper.vivoIntents(pkg).single())
        BackgroundActivityPermissionHelper.vivoPowerIntents(pkg).last().let { register(it) }

        assertTrue(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.VIVO, 6))
        assertTrue(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.VIVO, 6))
        assertTrue(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.VIVO, 7))
    }

    @Test
    fun `older OriginOS, Funtouch and unknown versions get no vivo rows`() {
        register(AutoStartPermissionHelper.vivoIntents(pkg).single())
        BackgroundActivityPermissionHelper.vivoPowerIntents(pkg).forEach { register(it) }
        for (major in listOf(5, 4, null)) {
            assertFalse(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.VIVO, major))
            assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.VIVO, major))
        }
    }

    @Test
    fun `a protected or unexported vivo page is not offered`() {
        register(AutoStartPermissionHelper.vivoIntents(pkg).single(), exported = false)
        register(BackgroundActivityPermissionHelper.vivoPowerIntents(pkg).first(), permission = "com.iqoo.powersaving.PRIVATE")
        register(BackgroundActivityPermissionHelper.vivoPowerIntents(pkg).last(), exported = false)
        assertFalse(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.VIVO, 6))
        assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.VIVO, 6))
    }

    @Test
    fun `the list page alone is enough for the power row`() {
        register(BackgroundActivityPermissionHelper.vivoPowerIntents(pkg).last())
        assertTrue(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.VIVO, 6))
    }

    @Test
    fun `Xiaomi rows are unchanged and other brands have none`() {
        assertTrue(AutoStartPermissionHelper.canOpenAutoStartSettings(context, OemBrand.XIAOMI, null))
        assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, OemBrand.XIAOMI, null))
        for (brand in listOf(OemBrand.OPPO, OemBrand.HUAWEI, OemBrand.SAMSUNG, OemBrand.OTHER)) {
            assertFalse(AutoStartPermissionHelper.canOpenAutoStartSettings(context, brand, 6))
            assertFalse(BackgroundActivityPermissionHelper.canOpenBackgroundActivitySettings(context, brand, 6))
        }
    }
}
