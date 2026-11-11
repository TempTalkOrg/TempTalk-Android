package com.difft.android.base.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
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


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftButtonPaletteTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private fun <T> inTheme(darkTheme: Boolean = false, read: @Composable () -> T): T {
        val results = mutableListOf<T>()
        composeTestRule.setContent {
            DifftTheme(darkTheme = darkTheme) { results += read() }
        }
        return results.first()
    }

    

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

        assertEquals(ColorTokens.Light.Primary, p.filledEnabledContainer)
        assertEquals(ColorTokens.Light.OnPrimary, p.filledEnabledContent)
        assertEquals(ColorTokens.Light.BackgroundDisabled, p.filledDisabledContainer)
        assertEquals(ColorTokens.Light.TextDisabled, p.filledDisabledContent)
        assertEquals(Color.Transparent, p.outlinedEnabledContainer)
        assertEquals(ColorTokens.Light.TextPrimary, p.outlinedEnabledContent)
        assertEquals(Color.Transparent, p.outlinedDisabledContainer)
        assertEquals(ColorTokens.Light.TextDisabled, p.outlinedDisabledContent)
        assertEquals(ColorTokens.Light.Fill, p.outlinedPressedContainer)
        assertEquals(ColorTokens.Light.Line, p.border)
    }

    @Test
    fun `dark colours follow the library tokens`() {
        val p = palette(darkTheme = true)

        assertEquals(ColorTokens.Dark.Primary, p.filledEnabledContainer)
        assertEquals(ColorTokens.Dark.OnPrimary, p.filledEnabledContent)
        assertEquals(ColorTokens.Dark.BackgroundDisabled, p.filledDisabledContainer)
        assertEquals(Color.Transparent, p.outlinedEnabledContainer)
        assertEquals(ColorTokens.Dark.TextPrimary, p.outlinedEnabledContent)
        assertEquals(Color.Transparent, p.outlinedDisabledContainer)
        assertEquals(ColorTokens.Dark.TextDisabled, p.outlinedDisabledContent)
        assertEquals(ColorTokens.Dark.Fill, p.outlinedPressedContainer)
        assertEquals(ColorTokens.Dark.Line, p.border)

        
        
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

        
        val small = inTheme { DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, true) }
        assertEquals(ColorTokens.Light.Primary, small)
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
        assertEquals(ColorTokens.Light.Primary, idle.second)
        assertEquals(ColorTokens.Light.Fill, pressed)
        assertEquals(ColorTokens.Light.TextDisabled, disabledContent)
    }

    @Test
    fun `danger and success darken under a black wash while pressed`() {
        assertEquals(Color.Black.copy(alpha = 0.2f), DifftButtonDefaults.PressOverlay)
        listOf(DifftButtonVariant.Danger, DifftButtonVariant.Success)
            .forEach { assertTrue("$it darkens", DifftButtonDefaults.hasPressOverlay(it)) }
        assertFalse(DifftButtonDefaults.hasPressOverlay(DifftButtonVariant.Primary))
        
        assertFalse(DifftButtonDefaults.hasPressOverlay(DifftButtonVariant.Secondary))
        assertFalse(DifftButtonDefaults.hasPressOverlay(DifftButtonVariant.Text))
    }

    @Test
    fun `dark primary pressed uses secondary text fill and keeps onPrimary content`() {
        val (pressed, disabled, content) = inTheme(darkTheme = true) {
            Triple(
                DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, pressed = true),
                DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, enabled = false, pressed = true),
                DifftButtonDefaults.contentColor(DifftButtonVariant.Primary),
            )
        }
        assertEquals(ColorTokens.Dark.PrimaryPressed, pressed)
        assertEquals(ColorTokens.Dark.BackgroundDisabled, disabled)
        assertEquals(ColorTokens.Dark.OnPrimary, content)
    }

    @Test
    fun `light primary pressed uses secondary text fill`() {
        val pressed = inTheme {
            DifftButtonDefaults.containerColor(DifftButtonVariant.Primary, pressed = true)
        }
        assertEquals(ColorTokens.Light.PrimaryPressed, pressed)
    }

    @Test
    fun `dark text and secondary buttons use neutral pressed fill`() {
        val colors = inTheme(darkTheme = true) {
            listOf(DifftButtonVariant.Text, DifftButtonVariant.Secondary).map { variant ->
                Triple(
                    DifftButtonDefaults.containerColor(variant, pressed = true),
                    DifftButtonDefaults.containerColor(variant, enabled = false, pressed = true),
                    DifftButtonDefaults.contentColor(variant),
                )
            }
        }
        colors.forEach { (pressed, disabled, content) ->
            assertEquals(ColorTokens.Dark.Fill, pressed)
            assertEquals(Color.Transparent, disabled)
            assertEquals(ColorTokens.Dark.Primary, content)
        }
    }

}
