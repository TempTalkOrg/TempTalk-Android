package com.difft.android.call.ui.screenshare

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The mic-queue avatar slot must reserve its 16dp footprint before the display info arrives, and
 * must do so without reaching for the Hilt entry point — this test runs with no Hilt graph.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class AvatarContentPlaceholderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `null avatar data renders the placeholder disc at the avatar size`() {
        composeTestRule.setContent {
            DifftTheme {
                Box(modifier = Modifier.testTag("slot")) {
                    AvatarContent(avatarData = null)
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("slot").assertWidthIsEqualTo(AVATAR_SIZE)
        composeTestRule.onNodeWithTag("slot").assertHeightIsEqualTo(AVATAR_SIZE)
    }
}
