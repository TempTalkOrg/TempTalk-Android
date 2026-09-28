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

/**
 * [DifftButton] contract (issue #1208, secondary variant #1217, library scale #1237): the four
 * design-library sizes are the single geometry truth, Secondary is the only outlined variant, an
 * explicit outer size beats the design minimum, press feedback is drawn from the pressed state
 * rather than a ripple, and the button has exactly two forms — interactive (its own click, with a
 * debounce) and visual-only (for the [com.difft.android.base.widget.DifftButtonView] shell).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftButtonTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Vertical to horizontal content padding, the pair the design states per size. */
    private fun DifftButtonSize.paddingPair(): Pair<Dp, Dp> {
        val padding = DifftButtonDefaults.contentPadding(this)
        assertEquals(padding.calculateTopPadding(), padding.calculateBottomPadding())
        assertEquals(
            padding.calculateLeftPadding(LayoutDirection.Ltr),
            padding.calculateRightPadding(LayoutDirection.Ltr),
        )
        return padding.calculateTopPadding() to padding.calculateLeftPadding(LayoutDirection.Ltr)
    }

    /** Reads theme-dependent defaults from inside a themed composition. */
    private fun <T> inTheme(darkTheme: Boolean = false, read: @Composable () -> T): T {
        val results = mutableListOf<T>()
        composeTestRule.setContent {
            DifftTheme(darkTheme = darkTheme) { results += read() }
        }
        return results.first()
    }

    // ---------- T1: geometry ----------

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

        // A two-letter label is narrower than 40dp on its own, so the minimum is what shows.
        composeTestRule.setContent {
            DifftTheme {
                DifftButton(text = "OK", onClick = {}, size = DifftButtonSize.S, modifier = Modifier.testTag(TAG))
            }
        }
        composeTestRule.onNodeWithTag(TAG).assertWidthIsAtLeast(40.dp)
    }

    // ---------- T2: typography ----------

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

        // Every label is Regular on the library scale — no Medium step on a button.
        actual.forEach { style ->
            assertEquals(FontWeight.Normal, style.fontWeight)
            assertEquals(0.sp, style.letterSpacing)
        }
        assertEquals(12.sp, actual[DifftButtonSize.S.ordinal].fontSize)
        assertEquals(14.sp, actual[DifftButtonSize.XL.ordinal].fontSize)
    }

    // ---------- T3: colours, light and dark ----------

    private data class Palette(
        val filledEnabledContainer: Color,
        val filledEnabledContent: Color,
        val filledDisabledContainer: Color,
        val filledDisabledContent: Color,
        val outlinedEnabledContainer: Color,
        val outlinedEnabledContent: Color,
        val outlinedDisabledContainer: Color,
        val outlinedDisabledContent: Color,
        val outlinedPressedContainer: Color,
        val border: Color,
    )

    private fun palette(darkTheme: Boolean): Palette = inTheme(darkTheme) {
        Palette(
            filledEnabledContainer = DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, true),
            filledEnabledContent = DifftButtonDefaults.contentColor(DifftButtonVariant.Primary, true),
            filledDisabledContainer = DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, false),
            filledDisabledContent = DifftButtonDefaults.contentColor(DifftButtonVariant.Primary, false),
            outlinedEnabledContainer = DifftButtonDefaults.containerColor(DifftButtonVariant.Secondary, true),
            outlinedEnabledContent = DifftButtonDefaults.contentColor(DifftButtonVariant.Secondary, true),
            outlinedDisabledContainer = DifftButtonDefaults.containerColor(DifftButtonVariant.Secondary, false),
            outlinedDisabledContent = DifftButtonDefaults.contentColor(DifftButtonVariant.Secondary, false),
            outlinedPressedContainer = DifftButtonDefaults.containerColor(
                DifftButtonVariant.Secondary,
                enabled = true,
                pressed = true,
            ),
            border = DifftButtonDefaults.borderColor(),
        )
    }

    @Test
    fun `light colours follow the library tokens`() {
        val p = palette(darkTheme = false)

        assertEquals(ColorTokens.Primary, p.filledEnabledContainer)
        assertEquals(ColorTokens.Light.TextOnPrimary, p.filledEnabledContent)
        assertEquals(ColorTokens.Light.BackgroundDisabled, p.filledDisabledContainer)
        assertEquals(ColorTokens.Light.TextDisabled, p.filledDisabledContent)
        assertEquals(Color.Transparent, p.outlinedEnabledContainer)
        assertEquals(ColorTokens.Light.TextPrimary, p.outlinedEnabledContent)
        assertEquals(Color.Transparent, p.outlinedDisabledContainer)
        assertEquals(ColorTokens.Light.TextDisabled, p.outlinedDisabledContent)
        assertEquals(ColorTokens.Light.BackgroundTertiary, p.outlinedPressedContainer)
        assertEquals(ColorTokens.Light.Line, p.border)
    }

    @Test
    fun `dark colours follow the library tokens`() {
        val p = palette(darkTheme = true)

        assertEquals(ColorTokens.Primary, p.filledEnabledContainer)
        assertEquals(ColorTokens.Dark.TextOnPrimary, p.filledEnabledContent)
        assertEquals(ColorTokens.Dark.BackgroundDisabled, p.filledDisabledContainer)
        assertEquals(Color.Transparent, p.outlinedEnabledContainer)
        assertEquals(ColorTokens.Dark.TextPrimary, p.outlinedEnabledContent)
        assertEquals(Color.Transparent, p.outlinedDisabledContainer)
        assertEquals(ColorTokens.Dark.TextDisabled, p.outlinedDisabledContent)
        assertEquals(ColorTokens.Dark.BackgroundTertiary, p.outlinedPressedContainer)
        assertEquals(ColorTokens.Dark.Line, p.border)

        // The disabled label on a filled button keeps the light grey in dark too: the dark
        // `textDisabled` would sit at ~1.3:1 on the dark disabled fill.
        assertEquals(ColorTokens.Light.TextDisabled, p.filledDisabledContent)
    }

    @Test
    fun `only the secondary variant is outlined`() {
        assertTrue(DifftButtonDefaults.isOutlined(DifftButtonVariant.Secondary))
        listOf(
            DifftButtonVariant.Primary,
            DifftButtonVariant.Danger,
            DifftButtonVariant.Success,
            DifftButtonVariant.Text,
        ).forEach { assertFalse("$it must be borderless", DifftButtonDefaults.isOutlined(it)) }

        // Size no longer forces a treatment: a small Primary is filled like any other.
        val small = inTheme { DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, true) }
        assertEquals(ColorTokens.Primary, small)
    }

    @Test
    fun `danger and success are filled with a white label and fade whole when disabled`() {
        val colors = inTheme {
            listOf(DifftButtonVariant.Danger, DifftButtonVariant.Success).map { variant ->
                Triple(
                    DifftButtonDefaults.containerColor(variant, true),
                    DifftButtonDefaults.contentColor(variant, true),
                    DifftButtonDefaults.contentColor(variant, false),
                )
            }
        }

        assertEquals(ColorTokens.Error, colors[0].first)
        assertEquals(ColorTokens.Success, colors[1].first)
        colors.forEach { (_, enabledContent, disabledContent) ->
            assertEquals(ColorTokens.Light.TextOnPrimary, enabledContent)
            // The disabled form is the same fill and label at 30% — never a grey recolour.
            assertEquals(ColorTokens.Light.TextOnPrimary, disabledContent)
        }
        assertTrue(DifftButtonDefaults.dimsWhenDisabled(DifftButtonVariant.Danger))
        assertTrue(DifftButtonDefaults.dimsWhenDisabled(DifftButtonVariant.Success))
        assertFalse(DifftButtonDefaults.dimsWhenDisabled(DifftButtonVariant.Primary))
        assertFalse(DifftButtonDefaults.dimsWhenDisabled(DifftButtonVariant.Secondary))
        assertEquals(0.3f, DifftButtonDefaults.DisabledAlpha, 0f)
    }

    @Test
    fun `the text variant has no fill until pressed`() {
        val (idle, pressed, disabledContent) = inTheme {
            Triple(
                DifftButtonDefaults.containerColor(DifftButtonVariant.Text, true) to
                    DifftButtonDefaults.contentColor(DifftButtonVariant.Text, true),
                DifftButtonDefaults.containerColor(DifftButtonVariant.Text, enabled = true, pressed = true),
                DifftButtonDefaults.contentColor(DifftButtonVariant.Text, false),
            )
        }

        assertEquals(Color.Transparent, idle.first)
        assertEquals(ColorTokens.Info, idle.second)
        assertEquals(ColorTokens.Light.BackgroundBlue, pressed)
        assertEquals(ColorTokens.Light.TextDisabled, disabledContent)
    }

    @Test
    fun `a filled variant darkens under a black wash while pressed`() {
        assertEquals(Color.Black.copy(alpha = 0.2f), DifftButtonDefaults.PressOverlay)
        listOf(
            DifftButtonVariant.Primary,
            DifftButtonVariant.Danger,
            DifftButtonVariant.Success,
        ).forEach { assertTrue("$it darkens", DifftButtonDefaults.hasPressOverlay(it)) }
        // The other two swap their fill instead, so a wash would double the feedback.
        assertFalse(DifftButtonDefaults.hasPressOverlay(DifftButtonVariant.Secondary))
        assertFalse(DifftButtonDefaults.hasPressOverlay(DifftButtonVariant.Text))
    }

    // ---------- T4 / T5: measured height ----------

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

    // ---------- T6 / T7 / T8: click paths ----------

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
        node.performClick() // must not crash: nothing consumes it
    }

    // ---------- Click debounce (issue #1208 RACE-1) ----------

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

        // Well past the window — Compose's own gesture injection moves the clock a little, so the
        // exact boundary is pinned on the View shell instead ([DifftButtonView]).
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

        // A deep sleep moves the realtime and wall clocks while uptime stands still. The window
        // must stay closed across it, so a device time change can never open it early.
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

    // ---------- T9 / T10: slot form and long labels ----------

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

        // The clickable Row merges its descendants, so both children show on the one button node.
        composeTestRule.onNodeWithTag(TAG).assertTextEquals("One", "Two")
        assertEquals(ColorTokens.Light.TextOnPrimary, captured.first())
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
