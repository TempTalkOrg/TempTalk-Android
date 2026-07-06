package com.difft.android.chat.gif

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.gif.GifPanelContract.GifTab
import com.difft.android.chat.gif.compose.GifGrid
import com.difft.android.chat.gif.compose.GifTabBar
import com.github.takahirom.roborazzi.RoborazziRule
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot baselines for the GIF panel chrome that renders without network:
 *  - T15: tab bar with TRENDING selected (38x38 / radius 8 / backgroundTertiary square).
 *  - T16: mood tabs greyed out (moodTabsEnabled=false -> textDisabled tint).
 *  - T17: empty-result grid state.
 *
 * GIF cells load animated webp via Glide over the network, which Roborazzi cannot render,
 * so the populated grid is not captured here; the tab bar and empty state are the
 * design-source-anchored, deterministic surfaces.
 *
 * Design source: Figma 16746:14100 (tab bar 16746:14115; selected square 16746:14181;
 * mood icons 16746:14161/14165). Light + dark captured for each.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class GifPanelScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val roborazziRule = RoborazziRule(
        composeRule = composeTestRule,
        captureRoot = composeTestRule.onRoot(),
        options = RoborazziRule.Options(captureType = RoborazziRule.CaptureType.None),
    )

    @Test
    fun `gif tab bar trending selected light`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = false) {
                GifTabBar(
                    selectedTab = GifTab.TRENDING,
                    favoritesEnabled = false,
                    moodTabsEnabled = false,
                    onTabClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DifftTheme.colors.background)
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/GifTabBar_trendingSelected_light.png")
    }

    @Test
    fun `gif tab bar trending selected dark`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                GifTabBar(
                    selectedTab = GifTab.TRENDING,
                    favoritesEnabled = false,
                    moodTabsEnabled = false,
                    onTabClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(DifftTheme.colors.background)
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/GifTabBar_trendingSelected_dark.png")
    }

    @Test
    fun `gif grid empty result light`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = false) {
                GifGrid(
                    items = emptyList(),
                    emptyResult = true,
                    onPick = {},
                    onLoadNextPage = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(DifftTheme.colors.background)
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/GifGrid_emptyResult_light.png")
    }

    @Test
    fun `gif grid empty result dark`() {
        composeTestRule.setContent {
            DifftTheme(darkTheme = true) {
                GifGrid(
                    items = emptyList(),
                    emptyResult = true,
                    onPick = {},
                    onLoadNextPage = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(DifftTheme.colors.background)
                )
            }
        }
        composeTestRule.onRoot()
            .captureRoboImage("screenshots/chat/GifGrid_emptyResult_dark.png")
    }
}
