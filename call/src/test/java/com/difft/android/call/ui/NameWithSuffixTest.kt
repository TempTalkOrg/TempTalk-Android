package com.difft.android.call.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class NameWithSuffixTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(direction: LayoutDirection, name: String, suffix: String) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                NameWithSuffix(
                    name = name,
                    suffix = suffix,
                    style = TextStyle(fontSize = 14.sp),
                    color = Color.White,
                    modifier = Modifier.width(200.dp),
                    suffixModifier = Modifier.testTag(SUFFIX),
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun nameLeft() = composeTestRule.onNodeWithTag(NAME_WITH_SUFFIX_NAME_TAG, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    private fun suffixLeft() = composeTestRule.onNodeWithTag(SUFFIX, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot.left

    @Test
    fun `an English suffix keeps the name first under an RTL layout`() {
        setContent(LayoutDirection.Rtl, "Alice", "'s Screen Sharing")
        assertTrue(nameLeft() < suffixLeft())
    }

    @Test
    fun `an RTL suffix puts the name on the right`() {
        setContent(LayoutDirection.Ltr, "Alice", "شاشة")
        assertTrue(nameLeft() > suffixLeft())
    }

    @Test
    fun `a long name ellipsizes and leaves the suffix in place`() {
        setContent(LayoutDirection.Ltr, "Alexandria Catherine Montgomery ".repeat(3), "'s Screen Sharing")
        val suffix = composeTestRule.onNodeWithTag(SUFFIX, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val name = composeTestRule.onNodeWithTag(NAME_WITH_SUFFIX_NAME_TAG, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("suffix $suffix should start where the name $name ends", suffix.left >= name.right - 1f)
        assertTrue("suffix $suffix should stay inside 200dp", suffix.right <= name.left + 200f * density() + 1f)
    }

    private fun density() = composeTestRule.density.density

    private companion object {
        const val SUFFIX = "suffix"
    }
}
