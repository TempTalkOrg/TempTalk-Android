package com.difft.android.call.ui.incoming

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The unlocked-phone tap buttons: each fires its callback on click, and nothing fires while disabled. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class IncomingCallControlsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var accepts = 0
    private var rejects = 0

    private fun setControls(enabled: Boolean = true) {
        accepts = 0
        rejects = 0
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                IncomingCallControls(
                    rejectIcon = ColorPainter(Color.Red),
                    acceptIcon = ColorPainter(Color.Green),
                    onReject = { rejects++ },
                    onAccept = { accepts++ },
                    enabled = enabled,
                    modifier = Modifier.width(320.dp),
                )
            }
        }
    }

    @Test
    fun `taps activate their own callback`() {
        setControls()
        composeTestRule.onNodeWithTag(IncomingCallControlsDefaults.ACCEPT_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(IncomingCallControlsDefaults.REJECT_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, accepts)
        assertEquals(1, rejects)
    }

    @Test
    fun `disabled ignores taps`() {
        setControls(enabled = false)
        composeTestRule.onNodeWithTag(IncomingCallControlsDefaults.ACCEPT_TEST_TAG).performClick()
        composeTestRule.onNodeWithTag(IncomingCallControlsDefaults.REJECT_TEST_TAG).performClick()
        composeTestRule.waitForIdle()
        assertEquals(0, accepts)
        assertEquals(0, rejects)
    }
}
