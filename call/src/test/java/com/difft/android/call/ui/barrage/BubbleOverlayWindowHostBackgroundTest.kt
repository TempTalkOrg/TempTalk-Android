package com.difft.android.call.ui.barrage

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.LCallViewModel
import com.difft.android.call.core.CallUiController
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertSame

/**
 * Regression pin (family K): asserts BubbleOverlayWindowHost's literal composable shape
 * (DifftTheme(darkTheme = true, applyWindowBackground = false) { BubbleOverlayLayer(...) },
 * BubbleOverlayWindowHost.kt:85-90) never writes the host window background — mirroring
 * M9/M12/M21's composable-direct-call + sentinel pattern.
 *
 * Deliberately does NOT mount the real CallContent: CallContent.kt:81's
 * viewModel.callType.collectAsState() is driven by CallTypeCoordinator's async room-metadata
 * flow, so an independent recomposition of CallContent's own unmodified DifftTheme (whose
 * applyWindowBackground defaults true) would reallocate a new ColorDrawable for a reason
 * unrelated to this test — breaking reference-identity assertions on the window background.
 *
 * Also deliberately does NOT go through BubbleOverlayWindowHost's own
 * WindowManager.addView(TYPE_APPLICATION_PANEL) path (:118): this codebase's own
 * BubbleOverlayDeferralIntegrationTest.kt documents that path as "not reasonably testable in
 * Robolectric" and never exercises it directly either.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BubbleOverlayWindowHostBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val fakeViewModel = mockk<LCallViewModel>(relaxed = true) {
        every { callUiController } returns CallUiController()
    }

    @Test
    fun `bubble overlay composable never writes the host window background across mount and dispose`() {
        val sentinel = ColorDrawable(Color.RED)
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        var mounted by mutableStateOf(true)
        composeTestRule.setContent {
            if (mounted) {
                DifftTheme(darkTheme = true, applyWindowBackground = false) {
                    BubbleOverlayLayer(viewModel = fakeViewModel)
                }
            }
        }
        composeTestRule.waitForIdle()
        assertSame(sentinel, composeTestRule.activity.window.decorView.background) // M19

        // Simulates BubbleOverlayWindowHost's DisposableEffect.onDispose (:149-152).
        mounted = false
        composeTestRule.waitForIdle()
        assertSame(sentinel, composeTestRule.activity.window.decorView.background) // M20
    }
}
