package com.difft.android.setting.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.sidechrome.NavigationPlacement
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.density.DisplaySize
import com.difft.android.chat.R
import com.difft.android.chat.ui.theme.ChatTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.difft.android.R as AppR








@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ThemeSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val systemLabel get() = context.getString(R.string.me_theme_system)
    private val lightLabel get() = context.getString(R.string.me_theme_light)
    private val darkLabel get() = context.getString(R.string.me_theme_dark)
    private val classicLabel get() = context.getString(R.string.chat_theme_classic)
    private val gradientLabel get() = context.getString(R.string.chat_theme_gradient)
    private val fontSizeLabel get() = context.getString(AppR.string.me_text_size)
    private val displaySizeLabel get() = context.getString(AppR.string.me_display_size)
    private val navigationLabel get() = context.getString(AppR.string.me_navigation_placement)

    private class Reports {
        val modes = mutableListOf<Int>()
        val chatThemes = mutableListOf<ChatTheme>()
        val largeText = mutableListOf<Boolean>()
        val displaySizes = mutableListOf<DisplaySize>()
        val placements = mutableListOf<NavigationPlacement>()
        val statusBar = mutableListOf<Boolean>()
    }

    private fun setContent(
        selectedMode: Int = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
        chatTheme: ChatTheme = ChatTheme.CLASSIC,
        largeText: Boolean = false,
        displaySize: DisplaySize = DisplaySize.STANDARD,
        navigationPlacement: NavigationPlacement? = NavigationPlacement.RIGHT,
        statusBarHidden: Boolean = false,
        width: Dp? = null,
    ): Reports {
        val reports = Reports()
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                Box(if (width != null) Modifier.width(width) else Modifier) {
                    ThemeSettingsContent(
                        selectedMode = selectedMode,
                        onSelect = { reports.modes += it },
                        chatTheme = chatTheme,
                        onSelectChatTheme = { reports.chatThemes += it },
                        largeText = largeText,
                        onLargeTextChange = { reports.largeText += it },
                        displaySize = displaySize,
                        onDisplaySizeChange = { reports.displaySizes += it },
                        navigationPlacement = navigationPlacement,
                        onNavigationPlacementChange = { reports.placements += it },
                        statusBarHidden = statusBarHidden,
                        onStatusBarHiddenChange = { reports.statusBar += it },
                    )
                }
            }
        }
        return reports
    }

    private fun label(text: String): SemanticsNodeInteraction =
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)

    
    private fun option(text: String): SemanticsNodeInteraction = composeTestRule.onNodeWithText(text)

    
    private fun segment(group: String, text: String): SemanticsNodeInteraction =
        composeTestRule.onNode(hasText(text) and hasAnyAncestor(hasContentDescription(group)))

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.single()
    }

    



    private fun isWhole(layout: TextLayoutResult): Boolean {
        val lines = 0 until layout.lineCount
        return lines.none { layout.isLineEllipsized(it) } &&
            layout.getLineEnd(layout.lineCount - 1) == layout.layoutInput.text.length &&
            !layout.didOverflowHeight &&
            lines.all { layout.getLineRight(it) - layout.getLineLeft(it) <= layout.layoutInput.constraints.maxWidth + 0.5f }
    }

    private fun assertOnlySelected(selected: String?, vararg others: String) {
        selected?.let { option(it).assertIsSelected() }
        others.forEach { option(it).assertIsNotSelected() }
    }

    
    @Test
    fun `every mode renders in order with the stored one selected`() {
        setContent(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        label(systemLabel).assertIsDisplayed()
        label(lightLabel).assertIsDisplayed()
        label(darkLabel).assertIsDisplayed()

        val systemLeft = option(systemLabel).getBoundsInRoot().left.value
        val lightLeft = option(lightLabel).getBoundsInRoot().left.value
        val darkLeft = option(darkLabel).getBoundsInRoot().left.value
        assertTrue(
            "expected System < Light < Dark left to right, measured $systemLeft / $lightLeft / $darkLeft",
            systemLeft < lightLeft && lightLeft < darkLeft,
        )

        assertOnlySelected(systemLabel, lightLabel, darkLabel)
    }

    
    @Test
    fun `tapping an unselected mode reports its bare mode value`() {
        val reports = setContent(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        option(darkLabel).performClick()

        assertEquals(listOf(AppCompatDelegate.MODE_NIGHT_YES), reports.modes)
    }

    
    @Test
    fun `tapping the already-selected mode still reports it`() {
        val reports = setContent(AppCompatDelegate.MODE_NIGHT_NO)

        option(lightLabel).performClick()

        assertEquals(listOf(AppCompatDelegate.MODE_NIGHT_NO), reports.modes)
    }

    
    @Test
    fun `a stored mode outside the three selects no option`() {
        setContent(UNKNOWN_MODE)

        assertOnlySelected(null, systemLabel, lightLabel, darkLabel)
    }

    
    @Test
    fun `the chat theme options report their theme and leave the color mode alone`() {
        val reports = setContent(AppCompatDelegate.MODE_NIGHT_NO, ChatTheme.CLASSIC)

        assertOnlySelected(classicLabel, gradientLabel)
        option(gradientLabel).performClick()

        assertEquals(listOf(ChatTheme.GRADIENT), reports.chatThemes)
        assertTrue(reports.modes.isEmpty())
        assertOnlySelected(lightLabel, systemLabel, darkLabel)
    }

    
    @Test
    fun `the four groups carry their titles and notes`() {
        setContent()

        listOf(
            R.string.me_color_mode,
            R.string.me_color_mode_note,
            R.string.chat_theme,
            R.string.chat_theme_note,
            R.string.me_text,
            AppR.string.me_screen_layout,
            AppR.string.me_screen_layout_note,
        ).forEach { label(context.getString(it)).assertExists() }
    }

    @Test
    fun `the text group shows the chat preview and its font size segments report large or not`() {
        val reports = setContent(largeText = false)

        composeTestRule.onNodeWithContentDescription(context.getString(R.string.me_text_preview)).assertExists()
        listOf(R.string.me_text_preview_notice, R.string.me_text_preview_received, R.string.me_text_preview_sent)
            .forEach { label(context.getString(it)).assertExists() }

        val large = segment(fontSizeLabel, context.getString(AppR.string.me_text_size_lage))
        segment(fontSizeLabel, context.getString(AppR.string.me_text_size_default)).assertIsSelected()
        large.performScrollTo().performClick()

        assertEquals(listOf(true), reports.largeText)
    }

    @Test
    fun `the selected font size reports nothing and the other one reports default`() {
        val reports = setContent(largeText = true)

        segment(fontSizeLabel, context.getString(AppR.string.me_text_size_lage)).performScrollTo().performClick()
        assertTrue(reports.largeText.isEmpty())
        segment(fontSizeLabel, context.getString(AppR.string.me_text_size_default)).performScrollTo().performClick()

        assertEquals(listOf(false), reports.largeText)
    }

    @Test
    fun `display size segments report their size`() {
        val reports = setContent(displaySize = DisplaySize.STANDARD)

        segment(displaySizeLabel, context.getString(AppR.string.me_display_size_standard)).assertIsSelected()
        segment(displaySizeLabel, context.getString(AppR.string.me_display_size_large)).performScrollTo().performClick()
        segment(displaySizeLabel, context.getString(AppR.string.me_display_size_small)).performScrollTo().performClick()

        assertEquals(listOf(DisplaySize.LARGE, DisplaySize.SMALL), reports.displaySizes)
    }

    @Test
    fun `the navigation row reports the placement where the setting applies`() {
        val reports = setContent(navigationPlacement = NavigationPlacement.RIGHT)

        segment(navigationLabel, context.getString(AppR.string.me_navigation_placement_right)).assertIsSelected()
        segment(navigationLabel, context.getString(AppR.string.me_navigation_placement_default)).performScrollTo().performClick()

        assertEquals(listOf(NavigationPlacement.DEFAULT), reports.placements)
    }

    @Test
    fun `without a navigation placement its row is absent`() {
        setContent(navigationPlacement = null)

        label(navigationLabel).assertDoesNotExist()
        label(displaySizeLabel).assertExists()
    }

    @Test
    fun `the status bar switch reports the new value`() {
        val reports = setContent(statusBarHidden = false)

        val switch = composeTestRule.onNodeWithText(context.getString(AppR.string.me_hide_status_bar))
        switch.assertIsOff()
        switch.performScrollTo().performClick()

        assertEquals(listOf(true), reports.statusBar)
    }

    @Test
    fun `the gradient theme's received bubble is the three veiled inks and the classic one a single colour`() {
        var classic: ChatBubbleColors? = null
        var gradient: ChatBubbleColors? = null
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                classic = chatBubbleColors(ChatTheme.CLASSIC)
                gradient = chatBubbleColors(ChatTheme.GRADIENT)
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(1, classic!!.received.size)
        assertEquals(null, classic!!.receivedShadow)
        val inks = gradient!!.received
        assertEquals("three different stops: $inks", 3, inks.distinct().size)
        
        assertTrue(inks.all { it.alpha == 1f })
        
        assertTrue(gradient!!.receivedShadow != null)
    }

    @Test
    fun `default text keeps every step at its size`() {
        setContent(largeText = false)

        assertEquals(16f, label(displaySizeLabel).textLayout().layoutInput.style.fontSize.value, 0.01f)
        assertEquals(16f, label(context.getString(R.string.me_text_preview_sent)).textLayout().layoutInput.style.fontSize.value, 0.01f)
    }

    @Test
    fun `large text enlarges the labels, the titles and the preview bubbles by one and a half`() {
        setContent(largeText = true)

        val row = label(displaySizeLabel).textLayout().layoutInput.style
        val bubble = label(context.getString(R.string.me_text_preview_sent)).textLayout().layoutInput.style
        val title = label(context.getString(R.string.me_text)).textLayout().layoutInput.style
        val segmentText = composeTestRule.onNode(
            hasText(context.getString(AppR.string.me_display_size_standard)) and hasAnyAncestor(hasContentDescription(displaySizeLabel)),
            useUnmergedTree = true,
        ).textLayout().layoutInput.style

        assertEquals(24f, row.fontSize.value, 0.01f)
        
        assertEquals(24f, bubble.fontSize.value, 0.01f)
        assertEquals(36f, bubble.lineHeight.value, 0.01f)
        assertEquals(21f, title.fontSize.value, 0.01f)
        assertEquals(19.5f, segmentText.fontSize.value, 0.01f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1000dp")
    fun `a wide row keeps the label and the control on one line with the control at the trailing edge`() {
        setContent(width = 600.dp)

        val labelBounds = label(navigationLabel).getUnclippedBoundsInRoot()
        val control = composeTestRule.onNodeWithContentDescription(navigationLabel).getUnclippedBoundsInRoot()
        assertTrue("control $control beside label $labelBounds", control.top < labelBounds.bottom && control.bottom > labelBounds.top)
        assertTrue(control.left > labelBounds.right)
        
        assertEquals(600f - 32f, control.right.value, 1f)
    }

    @Test
    fun `a narrow row with large text stacks the control below an uncut label, inside the row`() {
        setContent(largeText = true, width = NARROW)

        val labelNode = label(navigationLabel)
        
        val labelBounds = labelNode.getUnclippedBoundsInRoot()
        val control = composeTestRule.onNodeWithContentDescription(navigationLabel).getUnclippedBoundsInRoot()

        assertTrue("the navigation label is cut", isWhole(labelNode.textLayout()))
        assertTrue("control at ${control.top} is not below the label ending at ${labelBounds.bottom}", control.top >= labelBounds.bottom)
        assertEquals(NARROW.value - 32f, control.right.value, 1f)
        assertTrue("control starts at ${control.left}, outside the row", control.left.value >= 32f - 0.5f)

        val sizeControl = composeTestRule.onNodeWithContentDescription(displaySizeLabel).getUnclippedBoundsInRoot()
        assertTrue("the three-segment control is ${sizeControl.right - sizeControl.left} wide", sizeControl.left.value >= 32f - 0.5f)
        assertTrue(sizeControl.right.value <= NARROW.value - 32f + 0.5f)
    }

    @Test
    fun `at a narrow width with large text no color mode label is cut`() {
        setContent(largeText = true, width = NARROW)

        listOf(systemLabel, lightLabel, darkLabel).forEach { text ->
            val layout = label(text).textLayout()
            assertTrue("$text is cut", isWhole(layout))
            
            assertEquals("$text is broken inside the word", 1, layout.lineCount)
        }
    }

    @Test
    @Config(qualifiers = "zh")
    fun `in Chinese the system option reads as one label and breaks between its two words when it must wrap`() {
        setContent(largeText = true, width = NARROW)

        
        val layout = composeTestRule.onNode(hasText("跟随\n系统"), useUnmergedTree = true).textLayout()
        assertEquals(2, layout.lineCount)
        assertEquals("跟随".length, layout.getLineEnd(0, visibleEnd = true))
        assertTrue(isWhole(layout))
    }

    @Test
    @Config(qualifiers = "zh-w800dp-h1000dp")
    fun `in Chinese the system option is the plain four characters when it fits`() {
        setContent(width = 600.dp)

        option("跟随系统").assertIsSelected()
    }

    private companion object {
        
        const val UNKNOWN_MODE = 99

        
        val NARROW = 313.dp
    }
}
