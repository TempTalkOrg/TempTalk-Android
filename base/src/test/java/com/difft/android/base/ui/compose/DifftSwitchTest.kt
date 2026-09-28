package com.difft.android.base.ui.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [DifftSwitch] contract (issue #1206): the non-interactive form is exactly the library's Android
 * switcher track box — 52×32 — and the interactive form exposes switch semantics with a 48dp-tall
 * touch target and toggles on click.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DifftSwitchTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the Android switcher scale is a 52 by 32 track with a 24 on and 16 off knob`() {
        assertEquals(52.dp, DifftSwitchDefaults.TrackWidth)
        assertEquals(32.dp, DifftSwitchDefaults.TrackHeight)
        assertEquals(2.dp, DifftSwitchDefaults.TrackBorderWidth)

        assertEquals(24.dp, DifftSwitchDefaults.KnobSizeOn)
        assertEquals(16.dp, DifftSwitchDefaults.KnobSizeOff)
        assertEquals(4.dp, DifftSwitchDefaults.KnobInsetOn)
        assertEquals(8.dp, DifftSwitchDefaults.KnobInsetOff)

        // On: 4dp short of the track end. Off: 8dp past its start.
        assertEquals(24.dp, DifftSwitchDefaults.KnobOffsetOn)
        assertEquals(8.dp, DifftSwitchDefaults.KnobOffsetOff)
    }

    @Test
    fun `visual-only form is the 52 by 32 design box`() {
        composeTestRule.setContent {
            DifftTheme { DifftSwitch(checked = true, onCheckedChange = null, modifier = Modifier.testTag(TAG)) }
        }

        composeTestRule.onNodeWithTag(TAG)
            .assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(DifftSwitchDefaults.TrackHeight)
    }

    @Test
    fun `the off state keeps the same box as the on state`() {
        // Guards the off-state insets staying draw-only (border), never padding that would grow the box.
        composeTestRule.setContent {
            DifftTheme { DifftSwitch(checked = false, onCheckedChange = null, modifier = Modifier.testTag(TAG)) }
        }

        composeTestRule.onNodeWithTag(TAG)
            .assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(DifftSwitchDefaults.TrackHeight)
    }

    @Test
    fun `disabled veils the control at 40 percent without changing its box`() {
        // Figma's Disable-On / Disable-Off keep the enabled colours behind a 40% veil, so the
        // disabled switch must stay laid out exactly like the enabled one.
        assertEquals(0.4f, DifftSwitchDefaults.DisabledAlpha, 0f)

        composeTestRule.setContent {
            DifftTheme {
                DifftSwitch(
                    checked = true,
                    onCheckedChange = null,
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }

        composeTestRule.onNodeWithTag(TAG)
            .assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(DifftSwitchDefaults.TrackHeight)
    }

    @Test
    fun `interactive form has a 48dp touch target height and toggles on click`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftSwitch(checked = checked, onCheckedChange = { checked = it }, modifier = Modifier.testTag(TAG))
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.assertWidthIsEqualTo(DifftSwitchDefaults.TrackWidth)
            .assertHeightIsEqualTo(48.dp)
            .assertIsOff()
        node.performClick()
        node.assertIsOn()
        node.performClick()
        node.assertIsOff()
    }

    @Test
    fun `disabled interactive form does not toggle`() {
        composeTestRule.setContent {
            var checked by remember { mutableStateOf(false) }
            DifftTheme {
                DifftSwitch(
                    checked = checked,
                    onCheckedChange = { checked = it },
                    enabled = false,
                    modifier = Modifier.testTag(TAG),
                )
            }
        }
        val node = composeTestRule.onNodeWithTag(TAG)

        node.performClick()
        node.assertIsOff()
    }

    private companion object {
        const val TAG = "difft_switch"
    }
}
