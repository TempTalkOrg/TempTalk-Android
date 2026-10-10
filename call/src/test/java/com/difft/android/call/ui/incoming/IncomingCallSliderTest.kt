package com.difft.android.call.ui.incoming

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.call.service.TestScopeApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The slider's one job is that no tap or brush answers or declines, while a real drag past 60 % of
 * the travel does — exactly once per arming — and everything short of it springs back to centre.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = TestScopeApplication::class, sdk = [30])
class IncomingCallSliderTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private var answers = 0
    private var declines = 0
    private val enabledState = mutableStateOf(true)

    private fun setSlider(enabled: Boolean = true, layoutDirection: LayoutDirection = LayoutDirection.Ltr) {
        answers = 0
        declines = 0
        enabledState.value = enabled
        composeTestRule.setContent {
            val enabledNow by enabledState
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                DifftTheme(darkTheme = true) {
                    IncomingCallSlider(
                        answerIcon = ColorPainter(Color.Green),
                        declineIcon = ColorPainter(Color.Red),
                        declineLabel = "Decline",
                        answerLabel = "Answer",
                        hint = HINT,
                        onAnswer = { answers++ },
                        onDecline = { declines++ },
                        enabled = enabledNow,
                        animateIdle = false,
                        modifier = Modifier.width(320.dp),
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    private fun handleCentreX(): Float {
        val bounds = composeTestRule.onNodeWithTag(IncomingCallSliderDefaults.HANDLE_TEST_TAG).getBoundsInRoot()
        return ((bounds.left + bounds.right) / 2).value
    }

    /** Positive [distancePx] drags right (answer), negative drags left (decline). */
    private fun drag(distancePx: Float) {
        composeTestRule.onNodeWithTag(IncomingCallSliderDefaults.HANDLE_TEST_TAG).performTouchInput {
            swipe(start = center, end = Offset(center.x + distancePx, center.y), durationMillis = 200)
        }
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()
    }

    @Test
    fun `full drag right answers exactly once`() {
        setSlider()
        val restX = handleCentreX()
        drag(FULL_TRAVEL_PX)
        drag(FULL_TRAVEL_PX)
        assertEquals(1, answers)
        assertEquals(0, declines)
        assertTrue("handle should have moved right", handleCentreX() > restX + 10f)
    }

    @Test
    fun `full drag left declines exactly once`() {
        setSlider()
        val restX = handleCentreX()
        drag(-FULL_TRAVEL_PX)
        drag(-FULL_TRAVEL_PX)
        assertEquals(1, declines)
        assertEquals(0, answers)
        assertTrue("handle should have moved left", handleCentreX() < restX - 10f)
    }

    @Test
    fun `short drags spring back and a later full drag still answers`() {
        setSlider()
        val restX = handleCentreX()
        drag(SHORT_TRAVEL_PX)
        drag(-SHORT_TRAVEL_PX)
        assertEquals(0, answers)
        assertEquals(0, declines)
        assertTrue("handle back at rest", abs(handleCentreX() - restX) <= 1f)
        drag(FULL_TRAVEL_PX)
        assertEquals(1, answers)
    }

    @Test
    fun `tap shows the hint and activates nothing`() {
        setSlider()
        composeTestRule.onNodeWithText(HINT).assertDoesNotExist()
        composeTestRule.onNodeWithTag(IncomingCallSliderDefaults.HANDLE_TEST_TAG).performClick()
        composeTestRule.mainClock.advanceTimeBy(400)
        composeTestRule.waitForIdle()
        assertEquals(0, answers)
        assertEquals(0, declines)
        composeTestRule.onNodeWithText(HINT).assertIsDisplayed()
    }

    @Test
    fun `RTL keeps right = answer, with the answer label on the physical right`() {
        setSlider(layoutDirection = LayoutDirection.Rtl)
        val handleX = handleCentreX()
        val answerBounds = composeTestRule.onNodeWithText("Answer").getBoundsInRoot()
        assertTrue("answer label right of the handle", ((answerBounds.left + answerBounds.right) / 2).value > handleX)
        drag(FULL_TRAVEL_PX)
        assertEquals(1, answers)
        assertEquals(0, declines)
    }

    @Test
    fun `disabled ignores a full drag`() {
        setSlider(enabled = false)
        drag(FULL_TRAVEL_PX)
        assertEquals(0, answers)
    }

    @Test
    fun `re-enabling after a commit re-arms the slider`() {
        setSlider()
        drag(FULL_TRAVEL_PX)
        assertEquals(1, answers)
        enabledState.value = false
        composeTestRule.waitForIdle()
        enabledState.value = true
        composeTestRule.waitForIdle()
        drag(-FULL_TRAVEL_PX)
        assertEquals(1, declines)
    }

    @Test
    fun `commit threshold is sixty percent of the travel each way`() {
        assertEquals(0, IncomingCallSliderDefaults.commitDirection(59f, 100f))
        assertEquals(1, IncomingCallSliderDefaults.commitDirection(61f, 100f))
        assertEquals(-1, IncomingCallSliderDefaults.commitDirection(-61f, 100f))
        assertEquals(0, IncomingCallSliderDefaults.commitDirection(-59f, 100f))
        assertEquals(0, IncomingCallSliderDefaults.commitDirection(50f, 0f))
    }

    private companion object {
        const val HINT = "Swipe right to answer, left to decline"
        // Well past 60% of the travel at any test density; the offset is clamped to the track.
        const val FULL_TRAVEL_PX = 4_000f
        const val SHORT_TRAVEL_PX = 30f
    }
}
