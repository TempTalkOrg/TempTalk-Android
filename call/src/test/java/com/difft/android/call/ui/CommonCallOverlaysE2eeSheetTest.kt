package com.difft.android.call.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.difft.android.base.R as BaseR
import com.difft.android.base.ui.compose.e2ee.E2eeInfoSheetDialog
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.ResUtils
import com.difft.android.call.core.CallUiController
import com.difft.android.call.service.TestScopeApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Exercises `CommonCallOverlays`' new `showE2eeSheet` state + `E2eeInfoSheetDialog` mount
 * (`CallOverlays.kt`), covering T5-18/T5-19.
 *
 * Mirrors the exact production wiring snippet added to `CommonCallOverlays` — `showE2eeSheet`
 * state plus `viewModel.callUiController.isInPipMode.collectAsState(false)` gating
 * `E2eeInfoSheetDialog`'s `showSheet` param — against a real [CallUiController] and the real
 * (Task 1) [E2eeInfoSheetDialog]. Constructing the full `CommonCallOverlays` composable would
 * additionally require `MainPageWithBottomControlView`/participants-list/critical-alert
 * rendering off a fully room-wired `LCallViewModel`, far beyond what this sheet-wiring test
 * needs — same "mirror the exact production pattern" precedent as
 * `BubbleOverlayDeferralIntegrationTest`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class CommonCallOverlaysE2eeSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Composable
    private fun E2eeSheetHostUnderTest(callUiController: CallUiController, showSheetState: MutableState<Boolean>) {
        DifftTheme(darkTheme = true) {
            val isInPipModeForSheet by callUiController.isInPipMode.collectAsState(false)
            LaunchedEffect(isInPipModeForSheet) {
                if (isInPipModeForSheet) showSheetState.value = false
            }
            E2eeInfoSheetDialog(
                showSheet = showSheetState.value && !isInPipModeForSheet,
                learnMoreUrl = "https://yelling.pro/security",
                onDismissRequest = { showSheetState.value = false },
            )
        }
    }

    private val sheetTitle get() = ResUtils.getString(BaseR.string.e2ee_sheet_title)

    // ---------------------------------------------------------------------------------
    // T5-18 — showE2eeSheet=true (chained from a TopStatusBar click), !isInPipMode → visible.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5-18 - sheet mounts and becomes visible when showE2eeSheet is true and not in PiP`() {
        val callUiController = CallUiController()
        lateinit var showSheetState: MutableState<Boolean>

        composeTestRule.setContent {
            showSheetState = remember { mutableStateOf(false) }
            E2eeSheetHostUnderTest(callUiController, showSheetState)
        }

        composeTestRule.runOnIdle { showSheetState.value = true }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(sheetTitle).assertExists()
    }

    // ---------------------------------------------------------------------------------
    // T5-19 — showE2eeSheet=true then isInPipMode flips true → force-dismissed.
    // ---------------------------------------------------------------------------------
    @Test
    fun `T5-19 - sheet force-dismisses when PiP mode is entered while shown`() {
        val callUiController = CallUiController()
        lateinit var showSheetState: MutableState<Boolean>

        composeTestRule.setContent {
            showSheetState = remember { mutableStateOf(false) }
            E2eeSheetHostUnderTest(callUiController, showSheetState)
        }

        composeTestRule.runOnIdle { showSheetState.value = true }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(sheetTitle).assertExists()

        composeTestRule.runOnIdle { callUiController.setPipModeEnabled(true) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(sheetTitle).assertDoesNotExist()

        // Regression guard: returning FROM PiP must not resurrect the sheet. The underlying
        // `showE2eeSheet` state (not just the derived `showSheet` gate) is reset on PiP entry, so
        // leaving PiP with no new tap on the header must leave the sheet dismissed.
        composeTestRule.runOnIdle { callUiController.setPipModeEnabled(false) }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(sheetTitle).assertDoesNotExist()
    }
}
