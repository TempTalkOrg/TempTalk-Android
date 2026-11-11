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
        assertSame(sentinel, composeTestRule.activity.window.decorView.background)

        
        mounted = false
        composeTestRule.waitForIdle()
        assertSame(sentinel, composeTestRule.activity.window.decorView.background)
    }
}
