package com.difft.android.base.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.click
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
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
class AzIndexBarTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `accessible letter click invokes current callback`() {
        val selected = mutableListOf<String>()
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AzIndexBar(listOf("A", "B", "#"), { selected += it })
            }
        }
        rule.onNodeWithText("B").performClick()
        assertEquals(listOf("B"), selected)
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `short index condenses with dots keeping the ends and drag still visits every letter`() {
        val selected = mutableListOf<String>()
        val letters = ('A'..'Z').map { it.toString() } + "#"
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AzIndexBar(letters, { selected += it }, Modifier.heightIn(max = 320.dp).testTag("short_index"))
            }
        }
        
        rule.onNodeWithText("A").assertIsDisplayed()
        rule.onNodeWithText("#").assertIsDisplayed()
        rule.onAllNodesWithText("•").onFirst().assertIsDisplayed()
        rule.onNodeWithTag("short_index").performTouchInput { click(Offset(center.x, height - 1f)) }
        assertEquals("#", selected.last())
        selected.clear()
        rule.onNodeWithTag("short_index").performTouchInput {
            swipe(Offset(center.x, 1f), Offset(center.x, height - 1f), durationMillis = 400)
        }
        assertEquals("A", selected.first())
        assertEquals("#", selected.last())
        assertTrue("dragging across dots visits the letters they stand for", selected.size > 13)
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `a drag keeps going when the first letter resizes the index`() {
        val selected = mutableListOf<String>()
        val letters = ('A'..'Z').map { it.toString() } + "#"
        val maxHeight = mutableStateOf(200.dp)
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                
                AzIndexBar(letters, { selected += it; maxHeight.value = 330.dp },
                    Modifier.heightIn(max = maxHeight.value).testTag("growing_index"))
            }
        }
        rule.onNodeWithTag("growing_index").performTouchInput {
            swipe(Offset(center.x, 1f), Offset(center.x, height * 3f), durationMillis = 400)
        }
        assertEquals("A", selected.first())
        assertEquals("#", selected.last())
    }

    @Test
    fun `the pressed letter is highlighted after the letter list changes`() {
        val letters = mutableStateOf(listOf("A", "B", "C"))
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AzIndexBar(letters.value, {})
            }
        }
        rule.runOnIdle { letters.value = listOf("A", "B", "C", "D") }
        val idle = rule.onNodeWithText("B").captureToImage().toPixelMap()
        rule.onNodeWithText("B").performTouchInput { down(center) }
        rule.waitForIdle()
        val pressed = rule.onNodeWithText("B").captureToImage().toPixelMap()
        rule.onNodeWithText("B").performTouchInput { up() }
        val changed = (0 until idle.width).any { x -> (0 until idle.height).any { y -> idle[x, y] != pressed[x, y] } }
        assertTrue("the letter under the finger changes colour", changed)
    }

    @Test
    @Config(qualifiers = "mdpi")
    fun `index too short for three slots is hidden`() {
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AzIndexBar(listOf("A", "B", "C", "D"), {}, Modifier.heightIn(max = 30.dp))
            }
        }
        rule.onNodeWithText("A").assertDoesNotExist()
    }

    @Test
    fun `drag across the index selects covered letters and clamps at the end`() {
        val selected = mutableListOf<String>()
        rule.setContent {
            DifftTheme(applyWindowBackground = false) {
                AzIndexBar(listOf("A", "B", "#"), { selected += it })
            }
        }
        rule.onNodeWithText("A").performTouchInput {
            swipe(center, Offset(center.x, height * 4f), durationMillis = 300)
        }
        assertEquals("A", selected.first())
        assertTrue(selected.contains("B"))
        assertEquals("#", selected.last())
    }
}
