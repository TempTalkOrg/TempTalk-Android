package com.difft.android.setting

import android.app.NotificationManager
import com.difft.android.base.notification.CategoryLevel
import com.difft.android.base.notification.ChannelReading
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationReadiness
import com.difft.android.base.notification.NotificationReadinessEvaluator
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestResult
import com.difft.android.base.notification.NotificationTestResults
import com.difft.android.base.notification.OemBrand
import com.difft.android.base.notification.ReadinessInput
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

/** Row visibility, summaries and jumps of the notification settings page and its Calls and Critical Alerts pages. */
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
        running: Boolean = true,
        direct: Boolean = false,
        tests: NotificationTestResults = NotificationTestResults.NONE,
    ) = NotificationSettingsUiState(NotificationReadiness(items(*overrides), brand, tests), sdk, dnd, bypass, running, direct)

    private val c3Check = ReadinessItem(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK)
    private val n1Off = ReadinessItem(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF)
    private val c2Denied = ReadinessItem(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED)
    private val a1Off = ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_OFF)
    private val a1Low = ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_LOW_IMPORTANCE)

    private val on = SettingStatus(StatusLabel.ON, warning = false)
    private fun problem(label: StatusLabel) = SettingStatus(label, warning = true)

    @Test
    fun `the banner appears only when app notifications are proven off`() {
        assertFalse(state().showBanner)
        assertFalse(state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.UNKNOWN)).showBanner)
        assertTrue(state(n1Off).showBanner)
    }

    @Test
    fun `the app notifications group hides while the banner shows and says on only when read on`() {
        assertTrue(state().showAppNotificationsGroup)
        assertTrue(state().appNotificationsOn)
        assertFalse(state(n1Off).showAppNotificationsGroup)
        val unknown = state(ReadinessItem(ReadinessItemId.N1, ReadinessStatus.UNKNOWN))
        assertTrue(unknown.showAppNotificationsGroup)
        assertFalse(unknown.appNotificationsOn)
    }

    @Test
    fun `the app notifications row never says on where that page holds unreadable app-level switches`() {
        for (brand in listOf(OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.OPPO)) {
            assertFalse(state(brand = brand).appNotificationsOn, brand.name)
        }
        assertTrue(state(brand = OemBrand.SAMSUNG).appNotificationsOn)
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

    @Test
    fun `vivo with a resolvable vendor page opens it directly with its own caption`() {
        val direct = state(c3Check, brand = OemBrand.VIVO, direct = true)
        assertFalse(direct.oemPopupOpensGuide)
        assertTrue(direct.showVivoPopupHint)
        assertFalse(direct.showXiaomiPopupHint)

        val guide = state(c3Check, brand = OemBrand.VIVO)
        assertTrue(guide.oemPopupOpensGuide)
        assertFalse(guide.showVivoPopupHint)
    }

    @Test
    fun `the direct flag changes nothing for Xiaomi and Honor`() {
        val xiaomi = state(c3Check, brand = OemBrand.XIAOMI, direct = true)
        assertFalse(xiaomi.oemPopupOpensGuide)
        assertFalse(xiaomi.showVivoPopupHint)
        assertFalse(state(c3Check, brand = OemBrand.XIAOMI).oemPopupOpensGuide)

        assertTrue(state(c3Check, brand = OemBrand.HONOR).oemPopupOpensGuide)
        assertFalse(state(c3Check, brand = OemBrand.HONOR).showVivoPopupHint)
    }

    @Test
    fun `the full-screen row exists only from API 34 and the vendor row only on China OEM skins`() {
        assertNull(state(sdk = 33).fullScreenItem)
        assertNotNull(state(sdk = 34).fullScreenItem)
        assertNotNull(state(c3Check, sdk = 33, brand = OemBrand.VIVO).oemPopupItem)
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

    // ---------------------------------------------------------------- Calls summary

    @Test
    fun `the calls summary is off with a warning while app notifications are off`() {
        assertEquals(problem(StatusLabel.OFF), state(n1Off, c2Denied).callSummary)
    }

    @Test
    fun `the calls summary names a call category problem before full-screen display`() {
        fun c1(issue: ReadinessIssue) = ReadinessItem(ReadinessItemId.C1, ReadinessStatus.PROBLEM, issue)
        assertEquals(problem(StatusLabel.OFF), state(c1(ReadinessIssue.CALL_CATEGORY_OFF), c2Denied).callSummary)
        assertEquals(problem(StatusLabel.NO_BANNER), state(c1(ReadinessIssue.CALL_CATEGORY_NO_BANNER), c2Denied).callSummary)
        assertEquals(problem(StatusLabel.LOCK_HIDDEN), state(c1(ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN), c2Denied).callSummary)
    }

    @Test
    fun `the calls summary asks for full-screen display only where it is a runtime setting`() {
        assertEquals(problem(StatusLabel.NEEDS_FULL_SCREEN), state(c2Denied, sdk = 34).callSummary)
        assertEquals(on, state(c2Denied, sdk = 33).callSummary)
    }

    @Test
    fun `the calls summary says on only for a reading on a brand with readable vendor switches`() {
        assertEquals(on, state().callSummary)
        assertEquals(SettingStatus.NONE, state(brand = OemBrand.SAMSUNG).callSummary)
        assertEquals(SettingStatus.NONE, state(c3Check, brand = OemBrand.HONOR).callSummary)
        assertEquals(SettingStatus.NONE, state(ReadinessItem(ReadinessItemId.C1, ReadinessStatus.UNKNOWN)).callSummary)
        // A passed call test vouches for the category level but is no reading.
        val passed = NotificationTestResults(call = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp"))
        val tested = state(c3Check, brand = OemBrand.HONOR, tests = passed)
        assertEquals(CategoryLevel.TESTED_OK, tested.callLevel)
        assertEquals(SettingStatus.NONE, tested.callSummary)
        // Problems are still named on those brands.
        assertEquals(problem(StatusLabel.NEEDS_FULL_SCREEN), state(c2Denied, c3Check, brand = OemBrand.XIAOMI).callSummary)
    }

    // ---------------------------------------------------------------- Critical Alerts summary

    @Test
    fun `missing access reads off without a warning whatever else is wrong`() {
        assertEquals(SettingStatus(StatusLabel.OFF), state(dnd = false).criticalSummary)
        assertEquals(SettingStatus(StatusLabel.OFF), state(n1Off, a1Off, c2Denied, dnd = false, running = false).criticalSummary)
    }

    @Test
    fun `with access, app notifications off is a warning`() {
        assertEquals(problem(StatusLabel.OFF), state(n1Off).criticalSummary)
        assertEquals(problem(StatusLabel.OFF), state(n1Off, a1Low, c2Denied, running = false).criticalSummary)
    }

    @Test
    fun `the critical summary names the category before the bypass`() {
        assertEquals(problem(StatusLabel.OFF), state(a1Off, bypass = CriticalAlertBypass.NEEDS_USER_ACTION).criticalSummary)
        assertEquals(problem(StatusLabel.NO_BANNER), state(a1Low, bypass = CriticalAlertBypass.NEEDS_USER_ACTION).criticalSummary)
        assertEquals(problem(StatusLabel.DND_BLOCKED), state(c2Denied, bypass = CriticalAlertBypass.NEEDS_USER_ACTION, running = false).criticalSummary)
    }

    @Test
    fun `full-screen display comes before the background connection`() {
        assertEquals(problem(StatusLabel.NEEDS_FULL_SCREEN), state(c2Denied, running = false).criticalSummary)
        assertEquals(problem(StatusLabel.NEEDS_CONNECTION), state(c2Denied, sdk = 33, running = false).criticalSummary)
        assertEquals(problem(StatusLabel.NEEDS_CONNECTION), state(running = false).criticalSummary)
        // An unread bypass does not hide the connection problem.
        assertEquals(problem(StatusLabel.NEEDS_CONNECTION), state(bypass = CriticalAlertBypass.NOT_APPLICABLE, running = false).criticalSummary)
    }

    @Test
    fun `the critical summary says on once access is granted and no dependency has a definite problem`() {
        assertEquals(on, state().criticalSummary)
        // Unreadable parts do not hide that the feature is on; the category row stays silent instead.
        assertEquals(on, state(bypass = CriticalAlertBypass.NOT_APPLICABLE).criticalSummary)
        assertEquals(SettingStatus.NONE, state(bypass = CriticalAlertBypass.NOT_APPLICABLE).criticalCategoryStatus)
        // A missing or unreadable category is not "on": it may not post at all.
        assertEquals(SettingStatus.NONE, state(ReadinessItem(ReadinessItemId.A1, ReadinessStatus.UNKNOWN)).criticalSummary)
        // Below API 26 there is no channel bypass to read back.
        assertEquals(on, state(sdk = 25, bypass = CriticalAlertBypass.NOT_APPLICABLE).criticalSummary)
    }

    @Test
    fun `brands with unreadable vendor switches never read the category on but the feature still reads on`() {
        for (brand in listOf(OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.HUAWEI, OemBrand.SAMSUNG)) {
            assertEquals(on, state(brand = brand).criticalSummary, brand.name)
            assertEquals(SettingStatus.NONE, state(brand = brand).criticalCategoryStatus, brand.name)
            assertEquals(problem(StatusLabel.NEEDS_CONNECTION), state(brand = brand, running = false).criticalSummary, brand.name)
            assertEquals(problem(StatusLabel.NO_BANNER), state(a1Low, brand = brand).criticalCategoryStatus, brand.name)
        }
    }

    @Test
    fun `a hidden lock-screen override on the critical category is named on the row and the summary`() {
        val hidden = ReadinessItem(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_LOCK_HIDDEN)
        assertEquals(problem(StatusLabel.LOCK_HIDDEN), state(hidden).criticalCategoryStatus)
        assertEquals(problem(StatusLabel.LOCK_HIDDEN), state(hidden).criticalSummary)
    }

    // ---------------------------------------------------------------- Critical Alerts page

    @Test
    fun `the category row follows app notifications, the category and the bypass only`() {
        assertEquals(problem(StatusLabel.OFF), state(n1Off).criticalCategoryStatus)
        assertEquals(problem(StatusLabel.OFF), state(a1Off).criticalCategoryStatus)
        assertEquals(problem(StatusLabel.NO_BANNER), state(a1Low).criticalCategoryStatus)
        assertEquals(problem(StatusLabel.DND_BLOCKED), state(bypass = CriticalAlertBypass.NEEDS_USER_ACTION).criticalCategoryStatus)
        assertEquals(on, state(c2Denied, running = false).criticalCategoryStatus)
        assertEquals(SettingStatus.NONE, state(bypass = CriticalAlertBypass.NOT_APPLICABLE).criticalCategoryStatus)
        // Without access the bypass cannot be read back.
        assertEquals(SettingStatus.NONE, state(dnd = false, bypass = CriticalAlertBypass.NOT_APPLICABLE).criticalCategoryStatus)
    }

    @Test
    fun `the access row opens the access page to grant and the access list once granted`() {
        assertEquals(NotificationFix.DND_ACCESS, state(dnd = false).dndFix)
        assertEquals(NotificationFix.DND_ACCESS_LIST, state(dnd = true).dndFix)
    }

    @Test
    fun `an off connection is a warning on the critical page only once access is granted`() {
        assertTrue(state(dnd = true, running = false).backgroundConnectionWarning)
        assertFalse(state(dnd = false, running = false).backgroundConnectionWarning)
        assertFalse(state(dnd = true, running = true).backgroundConnectionWarning)
    }

    @Test
    fun `the background connection row mirrors whether the connection runs`() {
        assertTrue(state(running = true).backgroundConnectionOn)
        assertFalse(state(running = false).backgroundConnectionOn)
    }

    // ---------------------------------------------------------------- unreadable signals

    @Test
    fun `the vendor pop-up item and notification test results feed no summary or status`() {
        val ok = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp")
        val fail = NotificationTestResult(NotificationTestOutcome.FAIL, 1L, "fp")
        val testSets = listOf(
            NotificationTestResults.NONE,
            NotificationTestResults(banner = ok, lockscreen = ok, call = ok),
            NotificationTestResults(banner = fail, lockscreen = fail, call = fail),
        )
        val c3States = listOf(ReadinessStatus.OK, ReadinessStatus.PROBLEM, ReadinessStatus.UNKNOWN)
        for (brand in OemBrand.entries) {
            for (running in listOf(true, false)) {
                val baseline = state(brand = brand, running = running)
                for (tests in testSets) {
                    for (c3 in c3States) {
                        val s = state(
                            ReadinessItem(ReadinessItemId.C3, c3, ReadinessIssue.OEM_POPUP_CHECK),
                            brand = brand,
                            running = running,
                            tests = tests,
                        )
                        val where = "brand=$brand running=$running c3=$c3 tests=$tests"
                        assertEquals(baseline.callSummary, s.callSummary, where)
                        assertEquals(baseline.criticalSummary, s.criticalSummary, where)
                        assertEquals(baseline.criticalCategoryStatus, s.criticalCategoryStatus, where)
                    }
                }
            }
        }
    }

    @Test
    fun `the overview hint shows on skins with app-level switches while the app notifications group shows`() {
        for (brand in listOf(OemBrand.XIAOMI, OemBrand.VIVO, OemBrand.OPPO)) assertTrue(state(brand = brand).showOverviewHint, brand.name)
        for (brand in listOf(OemBrand.OTHER, OemBrand.SAMSUNG, OemBrand.HUAWEI)) assertFalse(state(brand = brand).showOverviewHint, brand.name)
        assertFalse(state(n1Off, brand = OemBrand.XIAOMI).showOverviewHint)
    }

    // Design §7: Xiaomi categories the user never set, through the evaluator into the page state.
    private fun xiaomiState(userSet: Boolean, brand: OemBrand = OemBrand.XIAOMI, dnd: Boolean = true, running: Boolean = true): NotificationSettingsUiState {
        val channel = ChannelReading(NotificationManager.IMPORTANCE_HIGH, hasSound = true, userSetImportance = userSet)
        val input = ReadinessInput(
            sdkInt = 34,
            postNotificationsGranted = true,
            appNotificationsEnabled = true,
            messageGroupBlocked = false,
            messageChannel = channel,
            callChannel = channel,
            criticalChannel = channel,
            canUseFullScreenIntent = true,
            oemBrand = brand,
        )
        return NotificationSettingsUiState(NotificationReadinessEvaluator.evaluate(input), 34, dnd, CriticalAlertBypass.ENABLED, running)
    }

    @Test
    fun `a never-set Xiaomi category asks for a check on every row and summary`() {
        val state = xiaomiState(userSet = false)
        assertEquals(CategoryLevel.NEEDS_CHECK, state.messageLevel)
        assertEquals(CategoryLevel.NEEDS_CHECK, state.callLevel)
        assertEquals(problem(StatusLabel.NEEDS_CHECK), state.callSummary)
        assertEquals(problem(StatusLabel.NEEDS_CHECK), state.criticalCategoryStatus)
        assertEquals(problem(StatusLabel.NEEDS_CHECK), state.criticalSummary)
        // The category comes before the connection in the Critical Alerts summary.
        assertEquals(problem(StatusLabel.NEEDS_CHECK), xiaomiState(userSet = false, running = false).criticalSummary)
        // Without Do Not Disturb access the feature is not in use.
        assertEquals(SettingStatus(StatusLabel.OFF), xiaomiState(userSet = false, dnd = false).criticalSummary)
    }

    @Test
    fun `a Xiaomi category the user has set keeps the original readings`() {
        val state = xiaomiState(userSet = true)
        assertEquals(CategoryLevel.UNKNOWN, state.messageLevel)
        assertEquals(CategoryLevel.UNKNOWN, state.callLevel)
        assertEquals(SettingStatus.NONE, state.callSummary)
        assertEquals(SettingStatus.NONE, state.criticalCategoryStatus)
        assertEquals(on, state.criticalSummary)
    }

    @Test
    fun `other brands ignore whether a category was set`() {
        val state = xiaomiState(userSet = false, brand = OemBrand.OTHER)
        assertEquals(CategoryLevel.ON, state.callLevel)
        assertEquals(on, state.callSummary)
        assertEquals(on, state.criticalCategoryStatus)
        assertEquals(on, state.criticalSummary)
        assertEquals(CategoryLevel.UNKNOWN, xiaomiState(userSet = false, brand = OemBrand.VIVO).callLevel)
    }

    @Test
    fun `the lock-screen note shows only on Xiaomi`() {
        assertTrue(state(brand = OemBrand.XIAOMI).showXiaomiLockScreenNote)
        for (brand in OemBrand.entries.filter { it != OemBrand.XIAOMI }) assertFalse(state(brand = brand).showXiaomiLockScreenNote, brand.name)
    }
}
