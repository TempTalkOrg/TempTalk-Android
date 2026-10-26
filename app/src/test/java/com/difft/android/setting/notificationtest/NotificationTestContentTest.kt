package com.difft.android.setting.notificationtest

import android.content.Context
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.notification.NotificationFix
import com.difft.android.base.notification.NotificationTestKind
import com.difft.android.base.notification.NotificationTestOutcome
import com.difft.android.base.notification.NotificationTestResult
import com.difft.android.base.notification.NotificationTestResults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Dialog
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Intent
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.Phase
import com.difft.android.setting.notificationtest.mvi.NotificationTestContract.State
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/** #1350: the notification test page renders its state and routes every action as an intent. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationTestContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val intents = mutableListOf<Intent>()

    private fun show(state: State) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                NotificationTestContent(state = state, onIntent = { intents += it })
            }
        }
    }

    @Test
    fun `untested rows offer a start that dispatches their test`() {
        show(State(loaded = true))
        NotificationTestKind.entries.forEach {
            composeTestRule.onNodeWithTag(testStatusTag(it)).assertTextContains(context.getString(R.string.notification_test_untested))
        }
        composeTestRule.onNodeWithTag(testButtonTag(NotificationTestKind.CALL)).performClick()
        assertEquals(listOf<Intent>(Intent.Start(NotificationTestKind.CALL)), intents)
    }

    @Test
    fun `a running test can be canceled while the others cannot start`() {
        show(State(loaded = true, running = NotificationTestKind.BANNER, phase = Phase.COUNTDOWN))
        composeTestRule.onNodeWithTag(testStatusTag(NotificationTestKind.BANNER))
            .assertTextContains(context.getString(R.string.notification_test_running))
        composeTestRule.onNodeWithTag(testButtonTag(NotificationTestKind.LOCKSCREEN)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(testButtonTag(NotificationTestKind.BANNER)).performClick()
        assertEquals(listOf<Intent>(Intent.Cancel), intents)
    }

    @Test
    fun `results show their own wording per test`() {
        val ok = NotificationTestResult(NotificationTestOutcome.OK, 1_700_000_000_000L, "fp")
        val fail = ok.copy(outcome = NotificationTestOutcome.FAIL)
        show(State(loaded = true, results = NotificationTestResults(banner = ok, lockscreen = fail, call = fail)))
        composeTestRule.onNodeWithTag(testStatusTag(NotificationTestKind.BANNER))
            .assertTextContains(context.getString(R.string.notification_level_confirmed), substring = true)
        composeTestRule.onNodeWithTag(testStatusTag(NotificationTestKind.LOCKSCREEN))
            .assertTextContains(context.getString(R.string.notification_test_not_seen), substring = true)
        composeTestRule.onNodeWithTag(testStatusTag(NotificationTestKind.CALL))
            .assertTextContains(context.getString(R.string.notification_test_not_shown), substring = true)
    }

    @Test
    fun `the question dialog dispatches the answer`() {
        show(State(loaded = true, running = NotificationTestKind.LOCKSCREEN, phase = Phase.POSTED, dialog = Dialog.Question(NotificationTestKind.LOCKSCREEN)))
        composeTestRule.onNodeWithText(context.getString(R.string.notification_test_lockscreen_question)).assertExists()
        composeTestRule.onNodeWithText(context.getString(R.string.notification_test_no)).performClick()
        assertEquals(listOf<Intent>(Intent.Answer(seen = false)), intents)
    }

    @Test
    fun `a failed call test with a settings fix offers that page`() {
        show(State(loaded = true, dialog = Dialog.Failed(NotificationTestKind.CALL, NotificationFix.OEM_POPUP)))
        composeTestRule.onNodeWithText(context.getString(R.string.notification_test_open_settings)).performClick()
        assertEquals(listOf<Intent>(Intent.OpenFix(NotificationFix.OEM_POPUP)), intents)
    }

    @Test
    fun `a failed call test without a settings fix offers the setup guide`() {
        show(State(loaded = true, dialog = Dialog.Failed(NotificationTestKind.CALL, null)))
        composeTestRule.onNodeWithText(context.getString(R.string.background_connection_guide_link)).performClick()
        assertEquals(listOf<Intent>(Intent.OpenGuide), intents)
    }

    @Test
    fun `a Do Not Disturb block only explains itself`() {
        show(State(loaded = true, dialog = Dialog.Blocked(PrecheckBlock.DND_ON, null)))
        composeTestRule.onNodeWithText(context.getString(R.string.notification_test_dnd_on)).assertExists()
        composeTestRule.onNodeWithText(context.getString(android.R.string.ok)).performClick()
        assertEquals(listOf<Intent>(Intent.DismissDialog), intents)
    }
}
