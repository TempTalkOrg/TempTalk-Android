package com.difft.android.base.ui.compose.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
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
class DifftSegmentedControlTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val options = listOf("Small", "Standard", "Large")

    private fun setContent(selected: Int, reported: MutableList<Int>, width: Dp? = null, withLeading: Boolean = false) {
        composeTestRule.setContent {
            DifftTheme {
                Box(if (width != null) Modifier.width(width) else Modifier) {
                    DifftSegmentedControl(
                        options = options,
                        selectedIndex = selected,
                        onSelect = { reported += it },
                        groupLabel = GROUP,
                        modifier = Modifier.testTag(TAG),
                        leading = if (withLeading) { _, _ -> Text("A") } else null,
                    )
                }
            }
        }
    }

    private fun segment(text: String) = composeTestRule.onNodeWithText(text)

    @Test
    fun `segments render in order with exactly the selected one marked`() {
        setContent(selected = 1, reported = mutableListOf())

        val lefts = options.map { segment(it).getBoundsInRoot().left.value }
        assertTrue("segments are not left to right: $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
        segment("Standard").assertIsSelected()
        segment("Small").assertIsNotSelected()
        segment("Large").assertIsNotSelected()
    }

    @Test
    fun `tapping another segment reports its index, tapping the selected one reports nothing`() {
        val reported = mutableListOf<Int>()
        setContent(selected = 1, reported = reported)

        segment("Standard").performClick()
        assertTrue(reported.isEmpty())
        segment("Large").performClick()
        segment("Small").performClick()

        assertEquals(listOf(2, 0), reported)
    }

    @Test
    fun `each segment is a full touch target and the group carries its label`() {
        setContent(selected = 0, reported = mutableListOf())

        options.forEach { option ->
            val bounds = segment(option).getBoundsInRoot()
            assertTrue("$option is ${bounds.height} tall", bounds.height >= 48.dp)
            assertTrue("$option is ${bounds.width} wide", bounds.width >= 64.dp)
        }
        composeTestRule.onNode(hasText("Large") and hasAnyAncestor(hasContentDescription(GROUP))).assertExists()
    }

    @Test
    fun `offered less than its natural width the control fills exactly that with equal segments`() {
        setContent(selected = 0, reported = mutableListOf(), width = 150.dp)

        val control = composeTestRule.onNodeWithTag(TAG).getBoundsInRoot()
        
        assertTrue("control is ${control.width} wide", control.width.value <= 150f && control.width.value > 150f - options.size)
        val widths = options.map { segment(it).getBoundsInRoot().width.value }
        assertEquals(widths[0], widths[1], 0.5f)
        assertEquals(widths[1], widths[2], 0.5f)
        assertTrue("segments leave the control: $widths", widths.sum() <= 150f)
    }

    @Test
    fun `a leading glyph is drawn but not announced`() {
        setContent(selected = 0, reported = mutableListOf(), withLeading = true)

        
        segment("Small").assertIsSelected()
        composeTestRule.onNode(hasText("A")).assertDoesNotExist()
    }

    private companion object {
        const val TAG = "segmented"
        const val GROUP = "Display size"
    }
}
