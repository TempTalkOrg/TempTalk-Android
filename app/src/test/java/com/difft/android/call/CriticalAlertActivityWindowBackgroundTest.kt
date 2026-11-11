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
