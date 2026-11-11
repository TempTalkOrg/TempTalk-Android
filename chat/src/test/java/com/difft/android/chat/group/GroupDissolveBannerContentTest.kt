package com.difft.android.chat.group

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "en")
class GroupDissolveBannerContentTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `countdown and keep hint render in a surface inset below the header`() {
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                Box(Modifier.fillMaxSize().testTag("host")) { GroupDissolveBanner(3, {}) }
            }
        }
        compose.onNodeWithText("3 days until dissolution").assertIsDisplayed()
        compose.onNodeWithText("Send a message or edit group info to keep it").assertIsDisplayed()
        val host = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        val banner = compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).fetchSemanticsNode()
        val density = compose.density.density
        org.junit.Assert.assertEquals(16 * density, banner.boundsInRoot.left - host.left, 1f)
        org.junit.Assert.assertEquals(16 * density, host.right - banner.boundsInRoot.right, 1f)
        org.junit.Assert.assertEquals(12 * density, banner.boundsInRoot.top - host.top, 1f)
    }

    @Test fun `close dispatches dismissal and hidden state removes the banner`() {
        val days = mutableStateOf<Int?>(1)
        var dismissals = 0
        compose.setContent {
            DifftTheme(applyWindowBackground = false) {
                GroupDissolveBanner(days.value, { dismissals++; days.value = null })
            }
        }
        compose.onNodeWithContentDescription("Close").performClick()
        assertEquals(1, dismissals)
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertDoesNotExist()
    }

    @Test fun `today copy and dark surface render without a glass source`() {
        compose.setContent {
            DifftTheme(darkTheme = true, applyWindowBackground = false) { GroupDissolveBanner(0, {}) }
        }
        compose.onNodeWithText("Dissolves today").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test fun `clearing the deadline removes the surface and a later update can show it`() {
        val days = mutableStateOf<Int?>(null)
        compose.setContent {
            DifftTheme(applyWindowBackground = false) { GroupDissolveBanner(days.value, {}) }
        }
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertDoesNotExist()
        compose.runOnIdle { days.value = 1 }
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertIsDisplayed()
        compose.runOnIdle { days.value = null }
        compose.onNodeWithTag(GROUP_DISSOLVE_BANNER_TAG).assertDoesNotExist()
    }
}
