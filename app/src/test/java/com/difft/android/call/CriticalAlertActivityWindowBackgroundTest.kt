package com.difft.android.call

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.R
import com.difft.android.call.ui.alert.CriticalAlertFullScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertTrue

/**
 * Regression pin (family I, user decision #6): asserts the migrated showCriticalAlertUI's literal
 * composable shape (DifftTheme(darkTheme = true, applyWindowBackground = false) {
 * CriticalAlertFullScreen(...) }) never writes to the host window, using the exact
 * TransparentActivityTheme resource CriticalAlertActivityManifestThemeTest (M21a) proved
 * CriticalAlertActivity's manifest points to. Mounts on a bare ComponentActivity, not the real
 * @AndroidEntryPoint CriticalAlertActivity, since this repo has no Hilt-Robolectric harness for
 * constructing Hilt activities directly in unit tests; the manifest-theme fact (M21a) + this
 * composable-level fact (M21/M22) compose to the same end-to-end claim a direct construction
 * would have proven.
 *
 * Cross-module R reference: R.style.TransparentActivityTheme is defined in :call
 * (call/src/main/res/values/themes.xml:3-8), while this test file is physically under :app's
 * test source set. gradle.properties' android.nonTransitiveRClass=true means :app's own R class
 * does not contain :call's resources — an implicit same-package R would resolve to the wrong (or
 * a non-existent) class, hence the explicit import below.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CriticalAlertActivityWindowBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `migrated showCriticalAlertUI composable never writes the window background`() {
        composeTestRule.activity.setTheme(R.style.TransparentActivityTheme)
        composeTestRule.activity.window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // A second composeTestRule.setContent {} call on the same activity throws
        // IllegalStateException ("already set content") on this repo's compose-ui-test version —
        // so onNewIntent's re-invocation of showCriticalAlertUI is simulated via a state flip that
        // forces a fresh recomposition of the same call site within a single setContent block,
        // rather than a second setContent call. This still proves "zero writers on every
        // invocation", not just the first onCreate.
        var alertArgs by mutableStateOf("t" to "m")
        composeTestRule.setContent {
            val (title, message) = alertArgs
            DifftTheme(darkTheme = true, applyWindowBackground = false) {
                CriticalAlertFullScreen(title = title, message = message, onJoinClick = {}, onCloseClick = {})
            }
        }
        composeTestRule.waitForIdle()
        assertTransparent()

        alertArgs = "t2" to "m2"
        composeTestRule.waitForIdle()
        assertTransparent()
    }

    private fun assertTransparent() {
        val background = composeTestRule.activity.window.decorView.background
        assertTrue(
            background == null || (background as? ColorDrawable)?.color == Color.TRANSPARENT,
            "window background must stay transparent — manifest TransparentActivityTheme (pinned " +
                "by M21a) is the sole source of truth once DifftTheme(applyWindowBackground = false) " +
                "removes the only competing writer",
        )
    }
}
