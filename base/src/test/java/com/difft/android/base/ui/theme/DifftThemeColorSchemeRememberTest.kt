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
