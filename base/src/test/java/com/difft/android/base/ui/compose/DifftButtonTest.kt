package com.difft.android.base.ui.compose

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.ui.theme.tokens.ColorTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration








@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    
    private fun DifftButtonSize.paddingPair(): Pair<Dp, Dp> {
        val padding = DifftButtonDefaults.contentPadding(this)
        assertEquals(padding.calculateTopPadding(), padding.calculateBottomPadding())
        assertEquals(
            padding.calculateLeftPadding(LayoutDirection.Ltr),
            padding.calculateRightPadding(LayoutDirection.Ltr),
        )
        return padding.calculateTopPadding() to padding.calculateLeftPadding(LayoutDirection.Ltr)
    }

    
    private fun <T> inTheme(darkTheme: Boolean = false, read: @Composable () -> T): T {
        val results = mutableListOf<T>()
        composeTestRule.setContent {
            DifftTheme(darkTheme = darkTheme) { results += read() }
        }
        return results.first()
    }

    

    @Test
    fun `each size carries the library height padding and corner radius`() {
        assertEquals(24.dp, DifftButtonDefaults.height(DifftButtonSize.S))
        assertEquals(32.dp, DifftButtonDefaults.height(DifftButtonSize.M))
        assertEquals(40.dp, DifftButtonDefaults.height(DifftButtonSize.L))
        assertEquals(48.dp, DifftButtonDefaults.height(DifftButtonSize.XL))

        assertEquals(4.dp to 8.dp, DifftButtonSize.S.paddingPair())
        assertEquals(6.dp to 12.dp, DifftButtonSize.M.paddingPair())
        assertEquals(8.dp to 16.dp, DifftButtonSize.L.paddingPair())
        assertEquals(12.dp to 16.dp, DifftButtonSize.XL.paddingPair())

        assertEquals(RoundedCornerShape(4.dp), DifftButtonDefaults.shape(DifftButtonSize.S))
        assertEquals(RoundedCornerShape(4.dp), DifftButtonDefaults.shape(DifftButtonSize.M))
        assertEquals(RoundedCornerShape(8.dp), DifftButtonDefaults.shape(DifftButtonSize.L))
        assertEquals(RoundedCornerShape(8.dp), DifftButtonDefaults.shape(DifftButtonSize.XL))
        assertEquals(1.dp, DifftButtonDefaults.BorderWidth)
    }

    @Test
    fun `a text button is the small radius at every size`() {
        DifftButtonSize.entries.forEach { size ->
            assertEquals(
                "text button at $size",
                RoundedCornerShape(4.dp),
                DifftButtonDefaults.shape(size, DifftButtonVariant.Text),
            )
        }
    }

    @Test
    fun `each size carries the library minimum width and no maximum`() {
        assertEquals(40.dp, DifftButtonDefaults.minWidth(DifftButtonSize.S))
        assertEquals(52.dp, DifftButtonDefaults.minWidth(DifftButtonSize.M))
        assertEquals(60.dp, DifftButtonDefaults.minWidth(DifftButtonSize.L))
        assertEquals(60.dp, DifftButtonDefaults.minWidth(DifftButtonSize.XL))

        
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(text = "OK", onClick = {}, size = DifftButtonSize.S, modifier = Modifier.testTag(TAG))
            }
        }
        composeTestRule.onNodeWithTag(TAG).assertWidthIsAtLeast(40.dp)
    }

    

    @Test
    fun `each size uses its step of the type scale`() {
        val (actual, expected) = inTheme {
            DifftButtonSize.entries.map { DifftButtonDefaults.textStyle(it) } to
                (DifftTheme.typography.p4 to DifftTheme.typography.p3)
        }
        val (p4, p3) = expected

        assertEquals(p4, actual[DifftButtonSize.S.ordinal])
        assertEquals(p3, actual[DifftButtonSize.M.ordinal])
        assertEquals(p3, actual[DifftButtonSize.L.ordinal])
        assertEquals(p3, actual[DifftButtonSize.XL.ordinal])

        
        actual.forEach { style ->
            assertEquals(FontWeight.Normal, style.fontWeight)
            assertEquals(0.sp, style.letterSpacing)
        }
        assertEquals(12.sp, actual[DifftButtonSize.S.ordinal].fontSize)
        assertEquals(14.sp, actual[DifftButtonSize.XL.ordinal].fontSize)
    }

    

    @Test
    fun `an unconstrained button is exactly the design height for its size`() {
        composeTestRule.setContent {
            DifftTheme {
                Column {
                    DifftButtonSize.entries.forEach { size ->
                        DifftButton(
                            text = size.name,
                            onClick = {},
                            size = size,
                            modifier = Modifier.testTag(size.name),
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithTag("S").assertHeightIsEqualTo(24.dp)
        composeTestRule.onNodeWithTag("M").assertHeightIsEqualTo(32.dp)
        composeTestRule.onNodeWithTag("L").assertHeightIsEqualTo(40.dp)
        composeTestRule.onNodeWithTag("XL").assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun `an explicit outer height wins over the size minimum`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(
                    text = "Accept",
                    onClick = {},
                    size = DifftButtonSize.L,
                    modifier = Modifier.height(36.dp).testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(36.dp)
    }

    

    @Test
    fun `the interactive form is a button node that reports one click`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme { DifftButton(text = "Next", onClick = { clicks++ }, modifier = Modifier.testTag(TAG)) }
        }

        val node = composeTestRule.onNodeWithTag(TAG)
        node.assertIsEnabled()
        node.assert(hasClickAction())
        node.performClick()

        assertEquals(1, clicks)
        assertEquals(Role.Button, node.fetchSemanticsNode().config[SemanticsProperties.Role])
    }

    @Test
    fun `a disabled button reports disabled and never calls back`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(text = "Next", onClick = { clicks++ }, enabled = false, modifier = Modifier.testTag(TAG))
            }
        }

        val node = composeTestRule.onNodeWithTag(TAG)
        node.assertIsNotEnabled()
        node.performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun `the visual-only form has no click action`() {
        composeTestRule.setContent {
            DifftTheme { DifftButton(text = "Next", onClick = null, modifier = Modifier.testTag(TAG)) }
        }

        val node = composeTestRule.onNodeWithTag(TAG)
        node.assertHasNoClickAction()
        node.performClick() 
    }

    

    @Test
    fun `a second click inside the debounce window is dropped and later clicks are not`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme { DifftButton(text = "Add", onClick = { clicks++ }, modifier = Modifier.testTag(TAG)) }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.performClick()
        node.performClick()
        node.performClick()
        assertEquals("only the first of a burst may get through", 1, clicks)

        
        
        ShadowSystemClock.advanceBy(Duration.ofMillis(DifftButtonDefaults.ClickDebounceMillis * 2))
        node.performClick()
        assertEquals("a click after the window must go through", 2, clicks)
    }

    @Test
    fun `the debounce window is measured on the uptime clock`() {
        var clicks = 0
        composeTestRule.setContent {
            DifftTheme { DifftButton(text = "Add", onClick = { clicks++ }, modifier = Modifier.testTag(TAG)) }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.performClick()
        assertEquals(1, clicks)

        
        
        val uptime = SystemClock.uptimeMillis()
        val realtime = SystemClock.elapsedRealtime()
        ShadowSystemClock.simulateDeepSleep(Duration.ofMillis(DifftButtonDefaults.ClickDebounceMillis * 2))
        assertEquals("uptime must not have moved", uptime, SystemClock.uptimeMillis())
        assertTrue("realtime must have moved", SystemClock.elapsedRealtime() > realtime)

        node.performClick()
        assertEquals("only uptime may open the window", 1, clicks)

        ShadowSystemClock.advanceBy(Duration.ofMillis(DifftButtonDefaults.ClickDebounceMillis * 2))
        node.performClick()
        assertEquals(2, clicks)
    }

    @Test
    fun `the click gate reopens exactly at the window's end`() {
        val gate = DifftButtonDefaults.ClickGate()

        assertTrue("the first click is accepted at any uptime", gate.tryAccept(0L))
        assertFalse(gate.tryAccept(DifftButtonDefaults.ClickDebounceMillis - 1))
        assertTrue(gate.tryAccept(DifftButtonDefaults.ClickDebounceMillis))
        assertFalse("and the accepted click restarts the window", gate.tryAccept(DifftButtonDefaults.ClickDebounceMillis))
    }

    

    @Test
    fun `the slot form renders every child under the variant content colour`() {
        val captured = mutableListOf<Color>()
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(onClick = {}, modifier = Modifier.testTag(TAG)) {
                    captured += LocalContentColor.current
                    Text("One")
                    Text("Two")
                }
            }
        }

        
        composeTestRule.onNodeWithTag(TAG).assertTextEquals("One", "Two")
        assertEquals(ColorTokens.Light.OnPrimary, captured.first())
    }

    @Test
    fun `a label wider than the button stays on one line at the design height`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(
                    text = "A very long call to action label that cannot possibly fit on one line",
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG).assertHeightIsEqualTo(48.dp)
    }

    private companion object {
        const val TAG = "difft_button"
    }
}
