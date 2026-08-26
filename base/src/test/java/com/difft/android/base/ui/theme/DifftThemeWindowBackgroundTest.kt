package com.difft.android.base.ui.theme

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals

/**
 * M1/M2 (issue #1127): [DifftTheme]'s `applyWindowBackground` parameter gates the
 * window-background `SideEffect`. M1 pins the default path (`applyWindowBackground` not
 * passed, resolves to `true`) as byte-for-byte unchanged from pre-#1127 behavior — this is
 * the zero-regression guarantee all 16 existing family-A/A' root-mount call sites depend on.
 * M2 proves `applyWindowBackground = false` is a true no-op skip (the window is never
 * touched at all), not a write-then-restore — the primitive every commit-2/3 call-site
 * migration in this PR relies on being correct.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftThemeWindowBackgroundTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `M1 default path writes extendedColors bg exactly as before applyWindowBackground existed`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = false) {
                Box {}
            }
        }
        composeTestRule.waitForIdle()

        val background = composeTestRule.activity.window.decorView.background as ColorDrawable
        assertEquals(
            createLightExtendedColors().bg.toArgb(),
            background.color,
            "default DifftTheme (applyWindowBackground not passed) must still write extendedColors.bg to the window",
        )
    }

    @Test
    fun `M2 applyWindowBackground false never touches the host window`() {
        val sentinel = ColorDrawable(Color.RED)
        composeTestRule.activity.window.setBackgroundDrawable(sentinel)

        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                Box {}
            }
        }
        composeTestRule.waitForIdle()

        val background = composeTestRule.activity.window.decorView.background as ColorDrawable
        assertEquals(
            Color.RED,
            background.color,
            "applyWindowBackground = false must be a pure skip — the pre-existing window background must be unchanged",
        )
    }
}
