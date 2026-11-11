package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
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
import com.difft.android.call.R as CallR
import com.difft.android.chat.R
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.chat.focus.settings.FocusEnd
import com.difft.android.chat.focus.settings.NotifyFocusCaption
import com.difft.android.chat.focus.settings.NotifyFocusSettingsContract
import com.difft.android.chat.focus.settings.StillNotifyItem
import com.difft.android.chat.util.CriticalAlertBypass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue





@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val fixes = mutableListOf<NotificationFix>()
    private var callsClicks = 0
    private var criticalClicks = 0
    private var guideClicks = 0
    private var backgroundClicks = 0
    private var testPageOpens = 0
    private val focusIntents = mutableListOf<NotifyFocusSettingsContract.Intent>()

    private enum class Page { MAIN, CALLS, CRITICAL }

    private fun item(id: ReadinessItemId, status: ReadinessStatus = ReadinessStatus.OK, issue: ReadinessIssue? = null) =
        ReadinessItem(id, status, issue)

    private val c3Check = item(ReadinessItemId.C3, ReadinessStatus.UNKNOWN, ReadinessIssue.OEM_POPUP_CHECK)
    private val n1Off = item(ReadinessItemId.N1, ReadinessStatus.PROBLEM, ReadinessIssue.APP_NOTIFICATIONS_OFF)
    private val failedCallTest = NotificationTestResults(call = NotificationTestResult(NotificationTestOutcome.FAIL, 1L, "fp"))

    private fun show(
        vararg overrides: ReadinessItem,
        page: Page = Page.MAIN,
        sdk: Int = 34,
        dnd: Boolean = true,
        brand: OemBrand = OemBrand.OTHER,
        bypass: CriticalAlertBypass = CriticalAlertBypass.ENABLED,
        running: Boolean = true,
        tests: NotificationTestResults = NotificationTestResults.NONE,
        direct: Boolean = false,
        focus: NotifyFocusSettingsContract.State = NotifyFocusSettingsContract.State(),
    ) {
        val items = listOf(ReadinessItemId.N1, ReadinessItemId.N2, ReadinessItemId.C1, ReadinessItemId.C2, ReadinessItemId.A1)
            .filter { id -> overrides.none { it.id == id } }
            .map { item(it) } + overrides
        val state = NotificationSettingsUiState(NotificationReadiness(items, brand, tests), sdk, dnd, bypass, running, direct)
        val subpageActions = NotificationSubpageActions(
            onFix = { fixes += it },
            onGuide = { guideClicks++ },
            onBackgroundConnection = { backgroundClicks++ },
        )
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                when (page) {
                    Page.MAIN -> NotificationSettingsContent(
                        state = state,
                        labels = NotificationSettingsLabels("All", "Name and content"),
                        actions = NotificationSettingsActions(
                            onFix = { fixes += it },
                            onCalls = { callsClicks++ },
                            onCriticalAlerts = { criticalClicks++ },
                            onGroupNotification = {},
                            onDisplayContent = {},
                            onBackgroundConnection = { backgroundClicks++ },
                            onGuide = { guideClicks++ },
                            onNotificationTest = { testPageOpens++ },
                        ),
                        focus = focus,
                        onFocusIntent = { focusIntents += it },
                    )
                    Page.CALLS -> CallNotificationSettingsContent(state, subpageActions)
                    Page.CRITICAL -> CriticalAlertSettingsContent(state, subpageActions)
                }
            }
        }
    }

    private fun text(id: Int) = context.getString(id)

    
    private fun tapCaptionLink(tag: String) {
        val node = composeTestRule.onNodeWithTag(tag).performScrollTo()
        val layout = mutableListOf<TextLayoutResult>()
        val getLayout = node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action
        checkNotNull(getLayout).invoke(layout)
        val result = layout.single()
        val box = result.getBoundingBox(result.layoutInput.text.length - 1)
        node.performTouchInput { click(box.center) }
    }

    private fun tap(tag: String) = composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun focusing(name: String) = NotifyFocusSettingsContract.State(
        featureEnabled = true,
        focusOn = true,
        caption = NotifyFocusCaption.Focusing(name),
        end = FocusEnd.Manual,
        othersStillNotify = listOf(StillNotifyItem.MENTION_ME),
        groupLevel = GlobalNotificationType.MENTION,
        groupNotificationPaused = true,
    )

    @Test
    fun `without the focus feature there is no focus card`() {
        show()
        composeTestRule.onAllNodesWithText(string(R.string.focus_group_chat)).assertCountEquals(0)
        composeTestRule.onNodeWithText("Name and content").assertIsDisplayed()
    }

    @Test
    fun `display stays with the message rows above the focus card`() {
        show(focus = NotifyFocusSettingsContract.State(featureEnabled = true))
        val display = composeTestRule.onNodeWithText("Name and content").fetchSemanticsNode().boundsInRoot.top
        val focusRow = composeTestRule.onNodeWithText(string(R.string.focus_group_chat)).fetchSemanticsNode().boundsInRoot.top
        assertTrue(display < focusRow)
    }

    @Test
    fun `focus off shows the switch with the hint below the card and no secondary rows`() {
        show(focus = NotifyFocusSettingsContract.State(featureEnabled = true))
        composeTestRule.onNodeWithText(string(R.string.focus_group_chat)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.focus_me_caption_off)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.focus_focusing)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(string(R.string.focus_end_time)).assertCountEquals(0)
    }

    @Test
    fun `while focusing the card names the group in its own row, states that others are muted and notes the paused level`() {
        show(focus = focusing("Alpha"))
        composeTestRule.onNodeWithText(string(R.string.focus_focusing)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Alpha").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.focus_caption_on)).performScrollTo().assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.focus_caption_other, "Alpha")).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.focus_paused)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.focus_paused_returns, string(R.string.notification_mention_only))).assertIsDisplayed()

        composeTestRule.onNodeWithText("Alpha").performClick()
        assertEquals(listOf<NotifyFocusSettingsContract.Intent>(NotifyFocusSettingsContract.Intent.TapSelectGroup), focusIntents)
    }

    @Test
    fun `an invalid focus offers the picker row and the red no-group caption`() {
        show(focus = focusing("").copy(caption = NotifyFocusCaption.Invalid, invalid = true))
        composeTestRule.onNodeWithText(string(R.string.focus_select_group)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(string(R.string.focus_focusing)).assertCountEquals(0)
        composeTestRule.onNodeWithText(string(R.string.focus_caption_invalid)).performScrollTo().assertIsDisplayed()
    }

    
    private fun assertNoteInRow(rowTag: String, note: String) {
        composeTestRule.onNodeWithTag(rowTag).assertTextContains(note)
        composeTestRule.onAllNodesWithText(note).assertCountEquals(1)
    }

    

    @Test
    fun `the banner replaces the app notifications group while app notifications are off`() {
        show(n1Off, brand = OemBrand.XIAOMI)
        composeTestRule.onNodeWithTag(TAG_BANNER).assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(TAG_GROUP_APP_NOTIFICATIONS).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(TAG_ACTION_NOTIFICATION_OVERVIEW).assertCountEquals(0)
        composeTestRule.onNodeWithText(text(R.string.notification_banner_enable)).performClick()
        assertEquals(listOf(NotificationFix.APP_NOTIFICATIONS), fixes)
    }

    @Test
    fun `without the banner the app notifications row reads on and opens the app notification page`() {
        show()
        composeTestRule.onAllNodesWithTag(TAG_BANNER).assertCountEquals(0)
        composeTestRule.onNodeWithTag(TAG_ROW_APP_NOTIFICATIONS)
            .assertTextContains(text(R.string.notification_app_notifications))
            .assertTextContains(text(R.string.notification_enable))
        tap(TAG_ROW_APP_NOTIFICATIONS)
        assertEquals(listOf(NotificationFix.APP_NOTIFICATIONS), fixes)
        
        composeTestRule.onAllNodesWithTag(TAG_ACTION_NOTIFICATION_OVERVIEW).assertCountEquals(0)
    }

    @Test
    fun `the vendor overview caption sits under app notifications and links there`() {
        show(sdk = 33, brand = OemBrand.XIAOMI)
        composeTestRule.onNode(hasTestTag(TAG_ACTION_NOTIFICATION_OVERVIEW) and hasAnyAncestor(hasTestTag(TAG_GROUP_APP_NOTIFICATIONS)))
            .assertExists()
            .assertTextContains(text(R.string.notification_overview_caption_xiaomi), substring = true)
            .assertTextContains(text(R.string.notification_overview_action), substring = true)
        tapCaptionLink(TAG_ACTION_NOTIFICATION_OVERVIEW)
        assertEquals(listOf(NotificationFix.APP_NOTIFICATIONS), fixes)
    }

    @Test
    fun `vivo gets its own overview caption`() {
        show(sdk = 33, brand = OemBrand.VIVO)
        composeTestRule.onNodeWithTag(TAG_ACTION_NOTIFICATION_OVERVIEW)
            .assertTextContains(text(R.string.notification_overview_caption_vivo), substring = true)
    }

    @Test
    fun `OPPO gets the generic overview caption`() {
        show(sdk = 33, brand = OemBrand.OPPO)
        composeTestRule.onNodeWithTag(TAG_ACTION_NOTIFICATION_OVERVIEW)
            .assertTextContains(text(R.string.notification_overview_caption), substring = true)
    }

    @Test
    fun `the top page has no lock-screen rows or critical captions`() {
        show(c3Check, brand = OemBrand.XIAOMI, dnd = false, running = false)
        composeTestRule.onAllNodesWithTag(TAG_ROW_FULL_SCREEN).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(TAG_ROW_OEM_POPUP).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Do Not Disturb", substring = true).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(text(R.string.critical_alert_connection_caption)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(text(R.string.notification_full_screen_note_calls)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(text(R.string.notification_oem_popup_note)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Other permissions", substring = true).assertCountEquals(0)
    }

    @Test
    fun `the calls row shows its summary and opens the calls page`() {
        show(item(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED))
        composeTestRule.onNodeWithTag(TAG_ROW_CALLS)
            .assertTextContains(text(R.string.notification_calls))
            .assertTextContains(text(R.string.notification_status_needs_full_screen))
        tap(TAG_ROW_CALLS)
        assertEquals(1, callsClicks)
        assertEquals(emptyList(), fixes)
    }

    @Test
    fun `the critical alerts row shows its summary and opens the critical alerts page`() {
        show(running = false)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL)
            .assertTextContains(text(R.string.critical_alerts))
            .assertTextContains(text(R.string.critical_alert_status_needs_connection))
        tap(TAG_ROW_CRITICAL)
        assertEquals(1, criticalClicks)
        assertEquals(emptyList(), fixes)
    }

    @Test
    fun `both summaries read on when everything reads on`() {
        show()
        composeTestRule.onNodeWithTag(TAG_ROW_CALLS).assertTextContains(text(R.string.notification_enable))
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL).assertTextContains(text(R.string.notification_enable))
    }

    @Test
    fun `an unreadable skin shows no on and no test result in any summary`() {
        val ok = NotificationTestResult(NotificationTestOutcome.OK, 1L, "fp")
        show(c3Check, sdk = 33, brand = OemBrand.OPPO, tests = NotificationTestResults(banner = ok, lockscreen = ok, call = ok))
        
        composeTestRule.onAllNodesWithText(text(R.string.notification_enable)).assertCountEquals(2)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL).assertTextContains(text(R.string.notification_enable))
        composeTestRule.onNodeWithTag(TAG_ROW_BACKGROUND).assertTextContains(text(R.string.notification_enable))
        composeTestRule.onAllNodesWithText(text(R.string.notification_level_confirmed)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(text(R.string.notification_test_passed)).assertCountEquals(0)
    }

    @Test
    fun `message row shows its status and opens its own category`() {
        show(item(ReadinessItemId.N2, ReadinessStatus.PROBLEM, ReadinessIssue.MESSAGE_CATEGORY_SILENT))
        composeTestRule.onNodeWithTag(TAG_ROW_MESSAGE).assertTextContains(text(R.string.notification_level_silent))
        tap(TAG_ROW_MESSAGE)
        assertEquals(listOf(NotificationFix.MESSAGE_CATEGORY), fixes)
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
    fun `a long value never hides the row title`() {
        val title = text(R.string.notification_group_chat_notification)
        val value = "A very long group notification value ".repeat(6)
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationSettingsContent(
                    state = null,
                    labels = NotificationSettingsLabels(value, "Name and content"),
                    actions = NotificationSettingsActions({}, {}, {}, {}, {}, {}, {}, {}),
                )
            }
        }
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        composeTestRule.onNodeWithText(value, substring = true).assertExists()
    }

    @Test
    fun `the background connection row shows its status and opens its page`() {
        show(running = false)
        composeTestRule.onNodeWithTag(TAG_ROW_BACKGROUND).performScrollTo()
            .assertTextContains(text(R.string.background_connection_status_off))
        tap(TAG_ROW_BACKGROUND)
        assertEquals(1, backgroundClicks)
    }

    @Test
    fun `the notification test link sits in the setup guide footer`() {
        show(brand = OemBrand.OTHER)
        composeTestRule.onNodeWithText(text(R.string.notification_test_link), substring = true)
            .performScrollTo()
            .assertExists()
    }

    

    @Test
    fun `the calls page shows the call category and full-screen rows with their own jumps`() {
        show(
            item(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_NO_BANNER),
            item(ReadinessItemId.C2, ReadinessStatus.PROBLEM, ReadinessIssue.FULL_SCREEN_DENIED),
            page = Page.CALLS,
        )
        composeTestRule.onNodeWithTag(TAG_ROW_CALL_CATEGORY)
            .assertTextContains(text(R.string.notification_call_notifications))
            .assertTextContains(text(R.string.notification_level_no_banner))
        composeTestRule.onNodeWithTag(TAG_ROW_FULL_SCREEN).assertTextContains(text(R.string.notification_disable))
        composeTestRule.onAllNodesWithTag(TAG_ROW_OEM_POPUP).assertCountEquals(0)
        assertNoteInRow(TAG_ROW_FULL_SCREEN, text(R.string.notification_full_screen_note_calls))
        composeTestRule.onAllNodesWithText(text(R.string.notification_full_screen_note_critical)).assertCountEquals(0)
        
        composeTestRule.onNode(hasTestTag(TAG_ROW_CALL_CATEGORY) and hasText(text(R.string.notification_full_screen_note_calls)))
            .assertDoesNotExist()
        tap(TAG_ROW_CALL_CATEGORY)
        tap(TAG_ROW_FULL_SCREEN)
        assertEquals(listOf(NotificationFix.CALL_CATEGORY, NotificationFix.FULL_SCREEN_INTENT), fixes)
    }

    @Test
    fun `below API 34 on a non-vendor skin the calls page has only the category row`() {
        show(page = Page.CALLS, sdk = 33)
        composeTestRule.onNodeWithTag(TAG_ROW_CALL_CATEGORY).assertExists()
        composeTestRule.onAllNodesWithTag(TAG_ROW_FULL_SCREEN).assertCountEquals(0)
        composeTestRule.onAllNodesWithTag(TAG_ROW_OEM_POPUP).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(text(R.string.notification_full_screen_note_calls)).assertCountEquals(0)
    }

    @Test
    fun `Xiaomi shows the permission editor row without a status, notes its switches and opens it`() {
        show(c3Check, page = Page.CALLS, brand = OemBrand.XIAOMI, tests = failedCallTest)
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP).assertTextContains(text(R.string.notification_oem_popup))
        
        composeTestRule.onAllNodesWithText(text(R.string.notification_test_not_shown)).assertCountEquals(0)
        assertNoteInRow(TAG_ROW_FULL_SCREEN, text(R.string.notification_full_screen_note_calls))
        assertNoteInRow(TAG_ROW_OEM_POPUP, text(CallR.string.notification_hint_oem_xiaomi))
        composeTestRule.onAllNodesWithText(text(R.string.notification_oem_popup_note)).assertCountEquals(0)
        
        composeTestRule.onAllNodesWithText(text(R.string.notification_enable)).assertCountEquals(0)
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(listOf(NotificationFix.OEM_POPUP), fixes)
        assertEquals(0, guideClicks)
    }

    @Test
    fun `vivo with a direct vendor page notes its switches in the vendor row and opens the page`() {
        show(c3Check, page = Page.CALLS, sdk = 33, brand = OemBrand.VIVO, direct = true)
        assertNoteInRow(TAG_ROW_OEM_POPUP, text(CallR.string.notification_hint_oem_vivo))
        composeTestRule.onAllNodesWithText(text(R.string.notification_oem_popup_note)).assertCountEquals(0)
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(listOf(NotificationFix.OEM_POPUP), fixes)
        assertEquals(0, guideClicks)
    }

    @Test
    fun `other China skins show the vendor settings row that opens the setup guide`() {
        show(c3Check, page = Page.CALLS, sdk = 33, brand = OemBrand.HONOR, tests = failedCallTest)
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP).assertTextContains(text(R.string.notification_vendor_settings))
        composeTestRule.onAllNodesWithText(text(R.string.notification_test_not_shown)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText("Other permissions", substring = true).assertCountEquals(0)
        assertNoteInRow(TAG_ROW_OEM_POPUP, text(R.string.notification_oem_popup_note))
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(1, guideClicks)
        assertEquals(emptyList(), fixes)
    }

    

    @Test
    fun `without access the critical page offers the access page and notes every dependency in its row`() {
        show(page = Page.CRITICAL, dnd = false, bypass = CriticalAlertBypass.NOT_APPLICABLE)
        composeTestRule.onNodeWithTag(TAG_ROW_DND_ACCESS)
            .assertTextContains(text(R.string.notification_dnd_access))
            .assertTextContains(text(R.string.notification_dnd_access_denied))
        assertNoteInRow(TAG_ROW_DND_ACCESS, text(R.string.critical_alert_dnd_caption))
        assertNoteInRow(TAG_ROW_CRITICAL_CATEGORY, text(R.string.critical_alert_category_note))
        assertNoteInRow(TAG_ROW_FULL_SCREEN, text(R.string.notification_full_screen_note_critical))
        assertNoteInRow(TAG_ROW_CRITICAL_BACKGROUND, text(R.string.critical_alert_connection_caption))
        composeTestRule.onAllNodesWithText(text(R.string.notification_full_screen_note_calls)).assertCountEquals(0)
        
        composeTestRule.onAllNodesWithText(text(R.string.notification_enable)).assertCountEquals(1)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL_BACKGROUND).assertTextContains(text(R.string.notification_enable))
        tap(TAG_ROW_DND_ACCESS)
        assertEquals(listOf(NotificationFix.DND_ACCESS), fixes)
    }

    @Test
    fun `with access every row reads on and opens its own page`() {
        show(page = Page.CRITICAL)
        composeTestRule.onNodeWithTag(TAG_ROW_DND_ACCESS).assertTextContains(text(R.string.notification_dnd_access_granted))
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL_CATEGORY)
            .assertTextContains(text(R.string.notification_critical_alert_category))
            .assertTextContains(text(R.string.notification_enable))
        tap(TAG_ROW_DND_ACCESS)
        tap(TAG_ROW_CRITICAL_CATEGORY)
        tap(TAG_ROW_FULL_SCREEN)
        tap(TAG_ROW_CRITICAL_BACKGROUND)
        assertEquals(
            listOf(NotificationFix.DND_ACCESS_LIST, NotificationFix.CRITICAL_ALERT_CATEGORY, NotificationFix.FULL_SCREEN_INTENT),
            fixes,
        )
        assertEquals(1, backgroundClicks)
    }

    @Test
    fun `the category row names a blocked bypass and the background row an off connection`() {
        show(page = Page.CRITICAL, bypass = CriticalAlertBypass.NEEDS_USER_ACTION, running = false)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL_CATEGORY).assertTextContains(text(R.string.critical_alert_status_dnd_blocked))
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL_BACKGROUND).assertTextContains(text(R.string.background_connection_status_off))
    }

    @Test
    fun `the critical page shares the vendor row and never reads on for an unreadable skin`() {
        show(c3Check, page = Page.CRITICAL, brand = OemBrand.XIAOMI, tests = failedCallTest)
        composeTestRule.onNodeWithTag(TAG_ROW_OEM_POPUP).assertTextContains(text(R.string.notification_oem_popup))
        composeTestRule.onAllNodesWithText(text(R.string.notification_test_not_shown)).assertCountEquals(0)
        assertNoteInRow(TAG_ROW_OEM_POPUP, text(CallR.string.notification_hint_oem_xiaomi))
        assertNoteInRow(TAG_ROW_FULL_SCREEN, text(R.string.notification_full_screen_note_critical))
        
        composeTestRule.onAllNodesWithText(text(R.string.notification_enable)).assertCountEquals(1)
        tap(TAG_ROW_OEM_POPUP)
        assertEquals(listOf(NotificationFix.OEM_POPUP), fixes)
    }

    

    private val n2NeverSet = item(ReadinessItemId.N2, ReadinessStatus.PROBLEM, ReadinessIssue.MESSAGE_CATEGORY_NEVER_SET)
    private val c1NeverSet = item(ReadinessItemId.C1, ReadinessStatus.PROBLEM, ReadinessIssue.CALL_CATEGORY_NEVER_SET)
    private val a1NeverSet = item(ReadinessItemId.A1, ReadinessStatus.PROBLEM, ReadinessIssue.CRITICAL_CATEGORY_NEVER_SET)

    @Test
    fun `never-set Xiaomi categories ask for a check on the message row and both summaries`() {
        show(n2NeverSet, c1NeverSet, a1NeverSet, brand = OemBrand.XIAOMI)
        val check = text(R.string.notification_status_check)
        composeTestRule.onNodeWithTag(TAG_ROW_MESSAGE).assertTextContains(check)
        composeTestRule.onNodeWithTag(TAG_ROW_CALLS).assertTextContains(check)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL).assertTextContains(check)
        tap(TAG_ROW_MESSAGE)
        assertEquals(listOf(NotificationFix.MESSAGE_CATEGORY), fixes)
    }

    @Test
    fun `on Xiaomi the calls page asks for a check and notes the lock screen in the category row`() {
        show(c1NeverSet, c3Check, page = Page.CALLS, brand = OemBrand.XIAOMI)
        composeTestRule.onNodeWithTag(TAG_ROW_CALL_CATEGORY).assertTextContains(text(R.string.notification_status_check))
        assertNoteInRow(TAG_ROW_CALL_CATEGORY, text(R.string.notification_lock_screen_check_note))
        tap(TAG_ROW_CALL_CATEGORY)
        assertEquals(listOf(NotificationFix.CALL_CATEGORY), fixes)
    }

    @Test
    fun `on Xiaomi the critical category row asks for a check and carries one combined note`() {
        show(a1NeverSet, c3Check, page = Page.CRITICAL, brand = OemBrand.XIAOMI)
        composeTestRule.onNodeWithTag(TAG_ROW_CRITICAL_CATEGORY).assertTextContains(text(R.string.notification_status_check))
        assertNoteInRow(TAG_ROW_CRITICAL_CATEGORY, text(R.string.critical_alert_category_note_xiaomi))
        composeTestRule.onAllNodesWithText(text(R.string.critical_alert_category_note)).assertCountEquals(0)
        tap(TAG_ROW_CRITICAL_CATEGORY)
        assertEquals(listOf(NotificationFix.CRITICAL_ALERT_CATEGORY), fixes)
    }

    @Test
    fun `other brands keep the category rows without the lock-screen note`() {
        show(page = Page.CALLS, brand = OemBrand.VIVO)
        composeTestRule.onAllNodesWithText(text(R.string.notification_lock_screen_check_note)).assertCountEquals(0)
    }

    @Test
    fun `other brands keep the original critical category note`() {
        show(page = Page.CRITICAL, brand = OemBrand.SAMSUNG)
        assertNoteInRow(TAG_ROW_CRITICAL_CATEGORY, text(R.string.critical_alert_category_note))
        composeTestRule.onAllNodesWithText(text(R.string.critical_alert_category_note_xiaomi)).assertCountEquals(0)
    }
}
