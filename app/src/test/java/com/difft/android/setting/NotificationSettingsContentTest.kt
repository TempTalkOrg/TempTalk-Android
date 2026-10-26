package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
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
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.chat.util.CriticalAlertBypass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** Compose contract of the notification settings page: visibility, status text and one jump per row. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fixes = mutableListOf<NotificationFix>()
    private var criticalClicks = 0
    private var guideClicks = 0
    private var testPageOpens = 0

    private fun item(id: ReadinessItemId, status: ReadinessStatus = ReadinessStatus.OK, issue: ReadinessIssue? = null) =
        ReadinessItem(id, status, issue)

    private fun show(
        vararg overrides: ReadinessItem,
        sdk: Int = 34,
        dnd: Boolean = true,
        brand: OemBrand = OemBrand.OTHER,
        bypass: CriticalAlertBypass = CriticalAlertBypass.ENABLED,
        fcmUnavailable: Boolean = false,
        running: Boolean = true,
        tests: NotificationTestResults = NotificationTestResults.NONE,
    ) {
        val items = listOf(ReadinessItemId.N1, ReadinessItemId.N2, ReadinessItemId.C1, ReadinessItemId.C2, ReadinessItemId.A1)
            .filter { id -> overrides.none { it.id == id } }
            .map { item(it) } + overrides
        val state = NotificationSettingsUiState(NotificationReadiness(items, brand, tests), sdk, dnd, bypass, fcmUnavailable, running)
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationSettingsContent(
                    state = state,
                    labels = NotificationSettingsLabels("All", "Name and content"),
                    actions = NotificationSettingsActions(
                        onFix = { fixes += it },
                        onCriticalAlerts = { criticalClicks++ },
                        onGroupNotification = {},
                        onMessageSound = {},
                        onDisplayContent = {},
                        onBackgroundConnection = {},
                        onGuide = { guideClicks++ },
                        onNotificationTest = { testPageOpens++ },
                    ),
                )
            }
        }
    }

    private fun tap(tag: String) = composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()

    @Test
    fun `the banner shows only when app notifications are off and opens the app notification page`() {
        show(item(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF))
        composeTestRule.onNodeWithTag(TAG_BANNER).assertIsDisplayed()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_banner_enable)).performClick()
        assertEquals(listOf(NotificationFix.APP_NOTIFICATIONS), fixes)
    }

    @Test
    fun `no banner when app notifications are fine`() {
        show()
        composeTestRule.onAllNodesWithTag(TAG_BANNER).assertCountEquals(0)
    }

    @Test
    fun `the lock-screen rows follow API level and vendor`() {
        show(sdk = 33)
        composeTestRule.onAllNodesWithTag(TAG_GROUP_LOCK_SCREEN).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(TAG_ROW_FULL_SCREEN).assertCountEquals(0)
    }

    @Test
    fun `API 34 shows only the full-screen row for a non-China brand`() {
        show()
        composeTestRule.onNodeWithTag(TAG_ROW_FULL_SCREEN).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(TAG_ROW_OEM_POPUP).assertCountEquals(0)
    }

    @Test
    fun `Xiaomi shows the permission editor row with its caption and opens it`() {
        show(
            item(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK),
            sdk = 33,
            brand = OemBrand.XIAOMI,
        )
        composeTestRule.onNodeWithTag(TAG_GROUP_LOCK_SCREEN).assertExists()
        composeTestRule.onAllNodesWithTag(TAG_ROW_FULL_SCREEN).assertCountEquals(0)
        composeTestRule.onNodeWithText("Other permissions", substring = true).assertExists()
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP)
            .assertTextContains(context.getString(R.string.notification_oem_popup))
            .assertTextContains(context.getString(R.string.notification_status_check))
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(listOf(NotificationFix.OEM_POPUP), fixes)
        assertEquals(0, testPageOpens)
    }

    @Test
    fun `other China skins show one vendor row with no caption that opens the setup guide`() {
        show(
            item(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK),
            sdk = 33,
            brand = OemBrand.VIVO,
        )
        composeTestRule.onNodeWithText(context.getString(R.string.notification_vendor_settings)).assertExists()
        // Message, call and vendor rows all read "check" on a China skin until tests vouch for them.
        composeTestRule.onAllNodesWithText(context.getString(R.string.notification_status_check)).assertCountEquals(3)
        composeTestRule.onAllNodesWithText("Other permissions", substring = true).assertCountEquals(0)
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(1, guideClicks)
        assertEquals(0, testPageOpens)
        assertEquals(emptyList(), fixes)
    }

    // #1350 T2, T9, T10: valid test results replace "check" on the rows of a China skin.
    @Test
    fun `test results show on the message, call and call pop-up rows`() {
        val ok = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp")
        show(
            item(ReadinessItemId.C3, ReadinessStatus.OK),
            sdk = 33,
            brand = OemBrand.OPPO,
            tests = NotificationTestResults(banner = ok, lockscreen = ok, call = ok),
        )
        composeTestRule.onNodeWithTag(TAG_ROW_MESSAGE).assertTextContains(context.getString(R.string.notification_level_confirmed))
        composeTestRule.onNodeWithTag(TAG_ROW_CALL).assertTextContains(context.getString(R.string.notification_test_passed))
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP).performScrollTo()
            .assertTextContains(context.getString(R.string.notification_test_passed))
    }

    @Test
    fun `a failed call test shows did-not-pop-up on the call pop-up row only`() {
        show(
            item(ReadinessItemId.C3, ReadinessStatus.PROBLEM, ReadinessIssue.OEM_POPUP_CHECK),
            sdk = 33,
            brand = OemBrand.HUAWEI,
            tests = NotificationTestResults(call = NotificationTestResult(NotificationTestOutcome.FAIL, 1L, "fp")),
        )
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP).performScrollTo()
            .assertTextContains(context.getString(R.string.notification_test_not_shown))
        composeTestRule.onNodeWithTag(TAG_ROW_CALL).assertTextContains(context.getString(R.string.notification_status_check))
    }

    @Test
    fun `the notification test link sits in the setup guide footer on every brand`() {
        show(brand = OemBrand.OTHER)
        composeTestRule.onNodeWithText(context.getString(R.string.notification_test_link), substring = true)
            .performScrollTo()
            .assertExists()
    }

    @Test
    fun `message and call rows show their status and open their own category`() {
        show(
            item(ReadinessItemId.N2, ReadinessStatus.PROBLEM, ReadinessIssue.MESSAGE_CATEGORY_SILENT),
            item(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_NO_BANNER),
            item(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED),
        )
        composeTestRule.onNodeWithText(context.getString(R.string.notification_level_silent)).assertExists()
        // The call row uses the message row's words; only full-screen keeps the generic status wording.
        composeTestRule.onNodeWithText(context.getString(R.string.notification_level_no_banner)).assertExists()
        composeTestRule.onAllNodesWithText(context.getString(R.string.notification_status_problem)).assertCountEquals(1)
        tap(TAG_ROW_MESSAGE)
        tap(TAG_ROW_CALL)
        tap(TAG_ROW_FULL_SCREEN)
        assertEquals(
            listOf(NotificationFix.MESSAGE_CATEGORY, NotificationFix.CALL_CATEGORY, NotificationFix.FULL_SCREEN_INTENT),
            fixes,
        )
    }

    @Test
    fun `a call category hidden on the lock screen shows the shared wording`() {
        show(item(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_LOCK_HIDDEN))
        composeTestRule.onNodeWithText(context.getString(R.string.notification_level_lock_hidden)).assertExists()
        tap(TAG_ROW_CALL)
        assertEquals(listOf(NotificationFix.CALL_CATEGORY), fixes)
    }

    @Test
    fun `no vendor row or caption without a vendor item`() {
        show(sdk = 33, brand = OemBrand.OTHER)
        composeTestRule.onAllNodesWithTag(TAG_ROW_OEM_POPUP).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Other permissions", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a long value never hides the row title`() {
        val title = context.getString(R.string.notification_message_sounds)
        val sound = "A very long custom notification sound name ".repeat(6)
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationSettingsContent(
                    state = null,
                    labels = NotificationSettingsLabels("All", "Name and content", messageSound = sound),
                    actions = NotificationSettingsActions({}, {}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onNodeWithText(sound, substring = true).assertExists()
    }

    @Test
    fun `definite problems add no explanatory text`() {
        show(
            item(ReadinessItemId.N2, ReadinessStatus.PROBLEM, ReadinessIssue.MESSAGE_CATEGORY_OFF),
            item(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_OFF),
        )
        composeTestRule.onAllNodesWithText("category", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun `critical alerts row delegates its jump and explains missing access`() {
        show(dnd = false)
        composeTestRule.onNodeWithText("Do Not Disturb", substring = true).assertExists()
        tap(TAG_ROW_CRITICAL)
        assertEquals(1, criticalClicks)
        assertEquals(emptyList(), fixes)
    }

    @Test
    fun `the critical alerts explanation is hidden once access is granted`() {
        show(dnd = true)
        composeTestRule.onAllNodesWithText("Do Not Disturb", substring = true).assertCountEquals(0)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL).assertTextContains(context.getString(R.string.notification_enable))
    }

    @Test
    fun `the delivery caption shows beside the access caption and independently of it`() {
        val delivery = context.getString(R.string.critical_alert_delivery_caption)
        show(dnd = true, fcmUnavailable = true, running = false)
        composeTestRule.onNodeWithText(delivery).assertExists()
        composeTestRule.onAllNodesWithText("Do Not Disturb", substring = true).assertCountEquals(0)
    }

    @Test
    fun `both captions show when access is missing and delivery is impossible`() {
        show(dnd = false, fcmUnavailable = true, running = false)
        composeTestRule.onNodeWithText(context.getString(R.string.critical_alert_delivery_caption)).assertExists()
        composeTestRule.onNodeWithText("Do Not Disturb", substring = true).assertExists()
    }

    @Test
    fun `no delivery caption when push works or the connection runs`() {
        show(fcmUnavailable = true, running = true)
        composeTestRule.onAllNodesWithText(context.getString(R.string.critical_alert_delivery_caption)).assertCountEquals(0)
    }
}
