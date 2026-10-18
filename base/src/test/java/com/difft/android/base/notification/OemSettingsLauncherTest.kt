package com.difft.android.base.notification

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** #1350 jump chains (design T9): a failing step falls through to the next and never throws. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OemSettingsLauncherTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun component(name: String) = Intent().setComponent(ComponentName("com.vendor", name))

    // T9
    @Test
    fun `a missing activity and a security failure fall through to the next step`() {
        val tried = mutableListOf<String?>()
        val opened = OemSettingsLauncher.startFirst(
            listOf(component("A"), component("B"), component("C")), "Test"
        ) { intent ->
            tried += intent.component?.className
            when (intent.component?.className) {
                "A" -> throw ActivityNotFoundException()
                "B" -> throw SecurityException("not exported")
            }
        }
        assertTrue(opened)
        assertEquals(listOf<String?>("A", "B", "C"), tried)
    }

    @Test
    fun `an unexpected failure also falls through`() {
        val tried = mutableListOf<String?>()
        val opened = OemSettingsLauncher.startFirst(listOf(component("A"), component("B")), "Test") { intent ->
            tried += intent.component?.className
            if (intent.component?.className == "A") throw IllegalStateException("boom")
        }
        assertTrue(opened)
        assertEquals(listOf<String?>("A", "B"), tried)
    }

    @Test
    fun `the walk stops at the first step that opens`() {
        val tried = mutableListOf<String?>()
        assertTrue(OemSettingsLauncher.startFirst(listOf(component("A"), component("B")), "Test") { tried += it.component?.className })
        assertEquals(listOf<String?>("A"), tried)
    }

    @Test
    fun `an exhausted chain reports failure instead of throwing`() {
        val opened = OemSettingsLauncher.startFirst(listOf(component("A")), "Test") { throw ActivityNotFoundException() }
        assertFalse(opened)
        assertFalse(OemSettingsLauncher.startFirst(emptyList(), "Test") { error("unreachable") })
    }

    @Test
    fun `fix chains always end at the app details page`() {
        NotificationFix.entries.forEach { fix ->
            OemBrand.entries.forEach { brand ->
                val last = NotificationFixJump.chain(context, fix, brand).last()
                assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, last.action, "fix=$fix brand=$brand")
            }
        }
    }

    @Test
    fun `category fixes target their own channel before the app page`() {
        fun channelOf(intent: Intent) = intent.getStringExtra(Settings.EXTRA_CHANNEL_ID)
        val message = NotificationFixJump.chain(context, NotificationFix.MESSAGE_CATEGORY, OemBrand.OTHER)
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, message[0].action)
        assertEquals(NotificationChannelIds.MESSAGE, channelOf(message[0]))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, message[1].action)

        val call = NotificationFixJump.chain(context, NotificationFix.CALL_CATEGORY, OemBrand.XIAOMI)
        assertEquals(NotificationChannelIds.CALL, channelOf(call[0]))

        val critical = NotificationFixJump.chain(context, NotificationFix.CRITICAL_ALERT_CATEGORY, OemBrand.OTHER)
        assertEquals(NotificationChannelIds.CRITICAL_ALERT, channelOf(critical[0]))
    }

    @Test
    fun `full-screen fix asks the system page then the app notification page`() {
        val chain = NotificationFixJump.chain(context, NotificationFix.FULL_SCREEN_INTENT, OemBrand.OTHER)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, chain[0].action)
        assertEquals("package:${context.packageName}", chain[0].dataString)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, chain[1].action)
    }

    @Test
    fun `only Xiaomi has a direct vendor page and every other brand has none to try`() {
        val xiaomi = NotificationFixJump.chain(context, NotificationFix.OEM_POPUP, OemBrand.XIAOMI)
        assertEquals("miui.intent.action.APP_PERM_EDITOR", xiaomi[0].action)
        assertEquals("com.miui.securitycenter", xiaomi[0].`package`)
        assertEquals(context.packageName, xiaomi[0].getStringExtra("extra_pkgname"))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, xiaomi.last().action)
        assertEquals(2, xiaomi.size)

        OemBrand.entries.filter { it != OemBrand.XIAOMI }.forEach {
            val chain = NotificationFixJump.chain(context, NotificationFix.OEM_POPUP, it)
            assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), chain.map { i -> i.action }, "brand=$it")
        }
    }

    @Test
    fun `every issue maps to a fix`() {
        ReadinessIssue.entries.forEach { NotificationFixJump.fixFor(it) }
        assertEquals(NotificationFix.CALL_CATEGORY, NotificationFixJump.fixFor(ReadinessIssue.CALL_CATEGORY_NO_BANNER))
        assertEquals(NotificationFix.OEM_POPUP, NotificationFixJump.fixFor(ReadinessIssue.OEM_POPUP_CHECK))
    }


    @Test
    fun `DND access chain tries the package detail page then the list then app details`() {
        val chain = NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, OemBrand.OTHER, sdkInt = 34)
        assertEquals(
            listOf("android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            chain.map { it.action },
        )
        assertEquals("package:${context.packageName}", chain[0].dataString)
    }

    @Test
    fun `DND access chain skips the detail page below API 29 and on Huawei and Honor`() {
        val lists = listOf(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        assertEquals(lists, NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, OemBrand.OTHER, sdkInt = 28).map { it.action })
        assertEquals(3, NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, OemBrand.OTHER, sdkInt = 29).size)
        listOf(OemBrand.HUAWEI, OemBrand.HONOR).forEach {
            assertEquals(lists, NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, it, sdkInt = 34).map { i -> i.action }, "brand=$it")
        }
        assertEquals(3, NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, OemBrand.XIAOMI, sdkInt = 34).size)
    }

    @Test
    fun `a launcher that rejects the first DND step falls through to the next`() {
        val tried = mutableListOf<String?>()
        val opened = OemSettingsLauncher.startFirst(
            NotificationFixJump.chain(context, NotificationFix.DND_ACCESS, OemBrand.OTHER, sdkInt = 34), "Test"
        ) { intent ->
            tried += intent.action
            if (intent.action == "android.settings.NOTIFICATION_POLICY_ACCESS_DETAIL_SETTINGS") throw ActivityNotFoundException()
        }
        assertTrue(opened)
        assertEquals(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, tried.last())
        assertEquals(2, tried.size)
    }

    @Test
    fun `the DND access list chain is the list then app details`() {
        listOf(OemBrand.OTHER, OemBrand.HUAWEI, OemBrand.XIAOMI).forEach {
            assertEquals(
                listOf(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
                NotificationFixJump.chain(context, NotificationFix.DND_ACCESS_LIST, it, sdkInt = 34).map { i -> i.action },
            )
        }
    }

    @Test
    fun `message category chain targets the channel then falls back and adds the conversation on API 31`() {
        val chain = NotificationFixJump.messageCategoryChain(context, "chat1", sdkInt = 34)
        assertEquals(
            listOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS, Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            chain.map { it.action },
        )
        assertEquals(NotificationChannelIds.MESSAGE, chain[0].getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals("chat1", chain[0].getStringExtra(Settings.EXTRA_CONVERSATION_ID))
        assertNull(NotificationFixJump.messageCategoryChain(context, "chat1", sdkInt = 30)[0].getStringExtra(Settings.EXTRA_CONVERSATION_ID))
        assertNull(NotificationFixJump.messageCategoryChain(context, null, sdkInt = 34)[0].getStringExtra(Settings.EXTRA_CONVERSATION_ID))
    }

    @Test
    fun `a page counts only when exported and its permission is absent or granted`() {
        assertTrue(OemSettingsLauncher.isStartable(exported = true, permission = null) { false })
        assertTrue(OemSettingsLauncher.isStartable(exported = true, permission = "") { false })
        assertTrue(OemSettingsLauncher.isStartable(exported = true, permission = "vendor.PERM") { true })
        assertFalse(OemSettingsLauncher.isStartable(exported = true, permission = "vendor.PERM") { false })
        assertFalse(OemSettingsLauncher.isStartable(exported = false, permission = null) { true })
    }

    @Test
    fun `resolving a vendor page ignores activities this app cannot start`() {
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        fun register(name: String, exported: Boolean, permission: String? = null): Intent {
            val info = android.content.pm.ActivityInfo().apply {
                this.name = name
                packageName = "com.vendor"
                this.exported = exported
                this.permission = permission
                applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = "com.vendor" }
            }
            pm.addOrUpdateActivity(info)
            return Intent().setComponent(ComponentName("com.vendor", name))
        }
        val open = register("Open", exported = true)
        val hidden = register("Hidden", exported = false)
        val guarded = register("Guarded", exported = true, permission = "com.vendor.PRIVATE")

        assertTrue(OemSettingsLauncher.canResolveAny(context, listOf(open)))
        assertFalse(OemSettingsLauncher.canResolveAny(context, listOf(hidden)))
        assertFalse(OemSettingsLauncher.canResolveAny(context, listOf(guarded)))
        assertFalse(OemSettingsLauncher.canResolveAny(context, listOf(hidden, guarded)))
        assertTrue(OemSettingsLauncher.canResolveAny(context, listOf(hidden, open)))
    }

    @Test
    fun `the background restriction row opens the battery usage page on API 31 and then app details`() {
        val chain = NotificationFixJump.chain(context, NotificationFix.BACKGROUND_RESTRICTION, OemBrand.OTHER, sdkInt = 34)
        assertEquals(
            listOf("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            chain.map { it.action },
        )
        assertEquals("package:${context.packageName}", chain[0].dataString)
        val old = NotificationFixJump.chain(context, NotificationFix.BACKGROUND_RESTRICTION, OemBrand.OTHER, sdkInt = 30)
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS), old.map { it.action })
    }

    @Test
    fun `a launcher that rejects the battery usage page falls back to app details`() {
        val tried = mutableListOf<String?>()
        val opened = OemSettingsLauncher.startFirst(
            NotificationFixJump.chain(context, NotificationFix.BACKGROUND_RESTRICTION, OemBrand.OTHER, sdkInt = 34), "Test"
        ) { intent ->
            tried += intent.action
            if (intent.action == "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL") throw SecurityException("blocked")
        }
        assertTrue(opened)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, tried.last())
    }
}
