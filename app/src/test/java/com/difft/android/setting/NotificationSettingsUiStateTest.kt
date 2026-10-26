package com.difft.android.setting

import com.difft.android.base.notification.CallPopupLevel
import com.difft.android.base.notification.CategoryLevel
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestResult
import com.difft.android.base.notification.NotificationTestResults
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessIssue
import com.difft.android.base.notification.ReadinessItem
import com.difft.android.base.notification.ReadinessItemId
import com.difft.android.base.notification.ReadinessStatus
import com.difft.android.chat.util.CriticalAlertBypass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Row visibility and status/jump rules of the notification settings page. */
class NotificationSettingsUiStateTest {

    private fun ok(id: ReadinessItemId) = ReadinessItem(id, ReadinessStatus.OK)

    private fun items(vararg overrides: ReadinessItem): List<ReadinessItem> {
        val base = listOf(
            ReadinessItemId.N1, ReadinessItemId.N2, ReadinessItemId.C1, ReadinessItemId.C2, ReadinessItemId.A1,
        ).map { ok(it) }
        return base.filter { b -> overrides.none { it.id == b.id } } + overrides
    }

    private fun state(
        vararg overrides: ReadinessItem,
        sdk: Int = 34,
        dnd: Boolean = true,
        bypass: CriticalAlertBypass = CriticalAlertBypass.ENABLED,
        brand: OemBrand = OemBrand.OTHER,
        fcmUnavailable: Boolean = false,
        running: Boolean = true,
    ) = NotificationSettingsUiState(NotificationReadiness(items(*overrides), brand), sdk, dnd, bypass, fcmUnavailable, running)

    private val c3Check = ReadinessItem(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK)

    @Test
    fun `the banner appears only when app notifications are proven off`() {
        assertFalse(state().showBanner)
        assertFalse(state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.UNKNOWN)).showBanner)
        assertTrue(state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF)).showBanner)
    }

    @Test
    fun `the full-screen row exists only on API 34 and above`() {
        assertNull(state(sdk = 33).fullScreenItem)
        assertNull(state(sdk = 29).fullScreenItem)
        assertNotNull(state(sdk = 34).fullScreenItem)
        assertNotNull(state(sdk = 36).fullScreenItem)
    }

    @Test
    fun `the vendor row exists only when the evaluator produced a vendor item`() {
        assertNull(state(brand = OemBrand.OTHER).oemPopupItem)
        assertNull(state(brand = OemBrand.SAMSUNG).oemPopupItem)
        assertNotNull(state(c3Check, brand = OemBrand.XIAOMI).oemPopupItem)
        assertTrue(state(c3Check, brand = OemBrand.XIAOMI).showXiaomiPopupHint)
        OemBrand.entries.filter { it.isChinaOem && it != OemBrand.XIAOMI }.forEach {
            assertFalse(state(c3Check, brand = it).showXiaomiPopupHint, "brand=$it")
        }
        assertFalse(state(brand = OemBrand.OTHER).showXiaomiPopupHint)
        assertFalse(state(brand = OemBrand.XIAOMI).showXiaomiPopupHint)
    }

    // #1350 T1, T2, T9, T10: the rows show the still-valid notification test results on China skins.
    @Test
    fun `the call pop-up and category rows follow the notification test results`() {
        val ok = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp")
        val fail = NotificationTestResult(NotificationTestOutcome.FAIL, 1L, "fp")
        fun tested(tests: NotificationTestResults) =
            NotificationSettingsUiState(NotificationReadiness(items(c3Check), OemBrand.HONOR, tests), 34, true, CriticalAlertBypass.ENABLED)
        val none = tested(NotificationTestResults.NONE)
        assertEquals(CallPopupLevel.UNTESTED, none.callPopupLevel)
        assertEquals(CategoryLevel.UNKNOWN, none.messageLevel)
        assertEquals(CategoryLevel.UNKNOWN, none.callLevel)
        val all = tested(NotificationTestResults(banner = ok, lockscreen = ok, call = ok))
        assertEquals(CategoryLevel.CONFIRMED, all.messageLevel)
        assertEquals(CategoryLevel.TESTED_OK, all.callLevel)
        assertEquals(CallPopupLevel.TESTED_OK, all.callPopupLevel)
        val failed = tested(NotificationTestResults(call = fail))
        assertEquals(CallPopupLevel.NOT_SHOWN, failed.callPopupLevel)
        assertEquals(CategoryLevel.UNKNOWN, failed.callLevel)
    }

    @Test
    fun `the lock-screen group is hidden when it has no rows`() {
        assertFalse(state(sdk = 33).showLockScreenGroup)
        assertTrue(state(sdk = 34).showLockScreenGroup)
        assertTrue(state(c3Check, sdk = 33, brand = OemBrand.VIVO).showLockScreenGroup)
    }

    @Test
    fun `message and call rows map their status`() {
        val silent = state(ReadinessItem(ReadinessItemId.N2, ReadinessStatus.PROBLEM, ReadinessIssue.MESSAGE_CATEGORY_SILENT))
        assertEquals(CategoryLevel.SILENT, silent.messageLevel)
        val missing = state(ReadinessItem(ReadinessItemId.N2, ReadinessStatus.UNKNOWN))
        assertEquals(CategoryLevel.UNKNOWN, missing.messageLevel)
        assertEquals(CategoryLevel.ON, state().messageLevel)
        assertEquals(CategoryLevel.ON, state().callLevel)
        val lowCall = state(ReadinessItem(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_NO_BANNER))
        assertEquals(CategoryLevel.NO_BANNER, lowCall.callLevel)
        val hidden = state(ReadinessItem(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN))
        assertEquals(CategoryLevel.LOCK_HIDDEN, hidden.callLevel)
        assertEquals(CategoryLevel.UNKNOWN, state(ReadinessItem(ReadinessItemId.C1, ReadinessStatus.UNKNOWN)).callLevel)
        val off = state(ReadinessItem(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_OFF))
        assertEquals(CategoryLevel.OFF, off.callLevel)
    }

    @Test
    fun `critical alerts are off without Do Not Disturb access and then open the access page`() {
        val noAccess = state(dnd = false)
        assertEquals(CriticalAlertStatus.OFF, noAccess.criticalStatus)
        assertEquals(NotificationFix.DND_ACCESS, noAccess.criticalFix)
        // An unmet category does not outrank the missing access.
        val both = state(ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_OFF), dnd = false)
        assertEquals(CriticalAlertStatus.OFF, both.criticalStatus)
        assertEquals(NotificationFix.DND_ACCESS, both.criticalFix)
    }

    @Test
    fun `once access is granted an unmet item opens the category page`() {
        val low = state(ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE))
        assertEquals(CriticalAlertStatus.NEEDS_ATTENTION, low.criticalStatus)
        val bypassOff = state(bypass = CriticalAlertBypass.NEEDS_USER_ACTION)
        assertEquals(CriticalAlertStatus.NEEDS_ATTENTION, bypassOff.criticalStatus)
        val unknown = state(ReadinessItem(ReadinessItemId.A1, ReadinessStatus.UNKNOWN))
        assertEquals(CriticalAlertStatus.CHECK, unknown.criticalStatus)
        listOf(low, bypassOff, unknown).forEach { assertEquals(NotificationFix.CRITICAL_ALERT_CATEGORY, it.criticalFix) }
    }

    @Test
    fun `critical alerts are off when app notifications are off and open the category page`() {
        val off = state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF))
        assertEquals(CriticalAlertStatus.OFF, off.criticalStatus)
        assertEquals(NotificationFix.CRITICAL_ALERT_CATEGORY, off.criticalFix)
    }

    @Test
    fun `the access caption follows missing access only`() {
        val noAccess = state(dnd = false)
        assertEquals(true, noAccess.showDndAccessCaption)
        val notificationsOff = state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF))
        assertEquals(CriticalAlertStatus.OFF, notificationsOff.criticalStatus)
        assertEquals(false, notificationsOff.showDndAccessCaption)
    }

    @Test
    fun `a bypass that could not be read back is not reported as on`() {
        val unread = state(bypass = CriticalAlertBypass.NOT_APPLICABLE)
        assertEquals(CriticalAlertStatus.CHECK, unread.criticalStatus)
        assertEquals(NotificationFix.CRITICAL_ALERT_CATEGORY, unread.criticalFix)
    }

    @Test
    fun `a verified-on row opens the access list so access can be reviewed or turned off`() {
        val on = state()
        assertEquals(CriticalAlertStatus.ON, on.criticalStatus)
        assertEquals(NotificationFix.DND_ACCESS_LIST, on.criticalFix)
    }

    @Test
    fun `delivery never changes the critical alerts status or jump`() {
        val offline = state(fcmUnavailable = true, running = false)
        assertEquals(CriticalAlertStatus.ON, offline.criticalStatus)
        assertEquals(NotificationFix.DND_ACCESS_LIST, offline.criticalFix)
    }

    @Test
    fun `the delivery caption shows only when push is unavailable and the background connection is off`() {
        assertFalse(state(fcmUnavailable = false, running = true).showCriticalDeliveryCaption)
        assertFalse(state(fcmUnavailable = false, running = false).showCriticalDeliveryCaption)
        assertFalse(state(fcmUnavailable = true, running = true).showCriticalDeliveryCaption)
        assertTrue(state(fcmUnavailable = true, running = false).showCriticalDeliveryCaption)
    }
}
