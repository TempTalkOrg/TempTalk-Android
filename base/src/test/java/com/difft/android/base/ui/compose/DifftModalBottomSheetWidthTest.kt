package com.difft.android.base.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Wide-screen contract for [DifftModalBottomSheet] (issue #1197): the sheet must stop at the
 * Material 640dp cap on a 1000dp window, and the Compose token must stay in sync with the
 * View-side `R.dimen.bottom_sheet_max_width` that [com.difft.android.base.widget.BaseBottomSheetDialogFragment] uses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1000dp-h800dp")
class DifftModalBottomSheetWidthTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun `sheet content is capped at 640dp on a 1000dp wide window`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftModalBottomSheet(onDismissRequest = {}) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .testTag(CONTENT_TAG)
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertWidthIsEqualTo(DifftBottomSheetDefaults.MaxWidth)
    }

    @Test
    fun `explicit sheetMaxWidth narrows the sheet`() {
        composeTestRule.setContent {
            DifftTheme {
                DifftModalBottomSheet(onDismissRequest = {}, sheetMaxWidth = 400.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp)
                            .testTag(CONTENT_TAG)
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag(CONTENT_TAG).assertWidthIsEqualTo(400.dp)
    }

    @Test
    fun `compose max width token matches the View dimen`() {
        val resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources
        val dimenPx = resources.getDimensionPixelSize(R.dimen.bottom_sheet_max_width)
        val tokenPx = (DifftBottomSheetDefaults.MaxWidth.value * resources.displayMetrics.density).toInt()

        assertEquals(dimenPx, tokenPx)
    }

    private companion object {
        const val CONTENT_TAG = "sheet_content"
    }
}
