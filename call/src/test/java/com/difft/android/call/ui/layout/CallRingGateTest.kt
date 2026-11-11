package com.difft.android.call.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import com.difft.android.call.layout.Posture
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode


@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [30], qualifiers = "w400dp-h800dp-xhdpi")
class CallRingGateTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `the callee shows once the fold settles, the placement and callee unchanged`() {
        val settled = mutableStateOf<Posture?>(null)
        val placement = RingPlacement(IntRect(0, 0, 800, 1600), Posture.FLAT)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                RingGate(placement, inPip = false, settled = settled.value) {
                    Box(Modifier.size(20.dp).testTag("callee"))
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("callee").assertDoesNotExist()
        val start = composeTestRule.mainClock.currentTime
        settled.value = Posture.FLAT
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()
        val elapsed = composeTestRule.mainClock.currentTime - start
        composeTestRule.onNodeWithTag("callee").assertExists()
        assertTrue("shown by the fold, not by the wait: $elapsed ms", elapsed < RING_SETTLE_MAX_MS)
    }

    @Test
    fun `without a fold report the callee shows after the wait`() {
        val placement = RingPlacement(IntRect(0, 0, 800, 1600), Posture.FLAT)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                RingGate(placement, inPip = false, settled = null) {
                    Box(Modifier.size(20.dp).testTag("callee"))
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("callee").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(RING_SETTLE_MAX_MS + 100)
        composeTestRule.onNodeWithTag("callee").assertExists()
    }
}
