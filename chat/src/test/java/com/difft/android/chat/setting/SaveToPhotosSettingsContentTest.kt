package com.difft.android.chat.setting

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.ui.compose.settings.DifftSettingsDefaults
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.chat.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [SaveToPhotosSettingsContent] contract (issue #1223): `null` is a real stored value ("follow the
 * global setting"), so it carries the checkmark, and the footer explains the group.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class SaveToPhotosSettingsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    private val selections = mutableListOf<Int?>()

    private fun setContent(current: Int?) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                SaveToPhotosSettingsContent(
                    selected = current,
                    defaultLabel = DEFAULT_LABEL,
                    onSelect = { selections += it },
                )
            }
        }
    }

    // T25
    @Test
    fun `the follow-global option is selected while the stored value is null`() {
        setContent(current = null)

        composeTestRule.onNodeWithText(DEFAULT_LABEL, useUnmergedTree = true).assertIsDisplayed()
        val checkmarks = composeTestRule
            .onAllNodesWithTag(DifftSettingsDefaults.CheckmarkTestTag, useUnmergedTree = true)
        checkmarks.assertCountEquals(1)

        // The single checkmark belongs to the follow-global row, so it sits inside that row's band.
        val mark = checkmarks[0].getBoundsInRoot()
        val label = composeTestRule
            .onNodeWithText(DEFAULT_LABEL, useUnmergedTree = true).getBoundsInRoot()
        assertTrue(
            "checkmark at ${mark.top} should overlap the default row at ${label.top}",
            mark.top.value < label.bottom.value && mark.bottom.value > label.top.value,
        )
        // Rendering alone reports nothing.
        assertEquals(emptyList<Int?>(), selections)
    }

    // T26
    @Test
    fun `tapping Never reports 0 and the footer explains the group`() {
        setContent(current = 1)

        composeTestRule
            .onNodeWithText(string(R.string.me_automatically_save_photos), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.save_to_photos_never), useUnmergedTree = true)
            .performClick()
        assertEquals(listOf(0), selections)
    }

    private companion object {
        const val DEFAULT_LABEL = "Default (Off)"
    }
}
