package com.difft.android.base.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * M3/M4/M5 (issue #1127, family L): [DifftTheme] wraps `colorScheme` construction in
 * `remember(darkTheme)`. Material3's [ColorScheme] has no `equals()`/`hashCode()` override
 * (reference identity only), so without `remember`, any DifftTheme recomposition — not just a
 * `darkTheme` flip — allocates a fresh instance and forces every `MaterialTheme.colorScheme.*`
 * reader to recompose needlessly.
 *
 * M3 forces DifftTheme to recompose for a reason unrelated to `darkTheme` (toggling
 * `useFlatBackground`, a real DifftTheme parameter that does not affect `colorScheme`) and
 * asserts the `colorScheme` instance is reused. M4 flips `darkTheme` itself and asserts a new
 * instance is produced with the correct field values — proving the fix does not break
 * dark-mode following. M5 is a plain unit test proving the two factory functions themselves are
 * pure (equal field values, different instances on each call) — the safety precondition
 * `remember(darkTheme)` relies on.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftThemeColorSchemeRememberTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `M3 colorScheme instance is reused when DifftTheme recomposes for an unrelated reason`() {
        var useFlatBackground by mutableStateOf(false)
        val capturedSchemes = mutableListOf<ColorScheme>()

        composeTestRule.setContent {
            DifftTheme(darkTheme = false, useFlatBackground = useFlatBackground) {
                capturedSchemes.add(MaterialTheme.colorScheme)
            }
        }
        composeTestRule.waitForIdle()

        // Toggling useFlatBackground changes a real DifftTheme argument (forcing recomposition
        // of DifftTheme itself, not just its content), while darkTheme stays constant — the
        // "unrelated reason" family L is guarding against.
        useFlatBackground = true
        composeTestRule.waitForIdle()

        assertEquals(2, capturedSchemes.size, "DifftTheme must have recomposed exactly once more")
        assertSame(
            capturedSchemes[0],
            capturedSchemes[1],
            "colorScheme must be the same instance across a recomposition that does not change darkTheme",
        )
    }

    @Test
    fun `M4 colorScheme instance and values change when darkTheme flips`() {
        var darkTheme by mutableStateOf(false)
        val capturedSchemes = mutableListOf<ColorScheme>()

        composeTestRule.setContent {
            DifftTheme(darkTheme = darkTheme) {
                capturedSchemes.add(MaterialTheme.colorScheme)
            }
        }
        composeTestRule.waitForIdle()

        darkTheme = true
        composeTestRule.waitForIdle()

        assertEquals(2, capturedSchemes.size, "DifftTheme must have recomposed exactly once more")
        assertNotSame(
            capturedSchemes[0],
            capturedSchemes[1],
            "colorScheme must be a new instance when darkTheme actually flips",
        )
        assertEquals(
            createDarkColorScheme().primary,
            capturedSchemes[1].primary,
            "the post-flip colorScheme must match the dark-theme factory output — remember(darkTheme) must not break theme following",
        )
    }

    @Test
    fun `M5 createLightColorScheme and createDarkColorScheme are pure`() {
        val light1 = createLightColorScheme()
        val light2 = createLightColorScheme()
        assertNotSame(light1, light2, "each call must allocate a fresh instance")
        assertEquals(light1.primary, light2.primary)
        assertEquals(light1.background, light2.background)

        val dark1 = createDarkColorScheme()
        val dark2 = createDarkColorScheme()
        assertNotSame(dark1, dark2, "each call must allocate a fresh instance")
        assertEquals(dark1.primary, dark2.primary)
        assertEquals(dark1.background, dark2.background)
    }
}
