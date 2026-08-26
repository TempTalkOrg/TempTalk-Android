package com.difft.android.base.ui.compose.e2ee

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.R
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.openExternalBrowser
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * T1-1..T1-3: [E2eeInfoSheetContent] is the shared explainer content both [E2eeInfoSheet.show]
 * and [E2eeInfoSheetDialog] host.
 *
 * Screen qualifiers are set generously tall (h1600dp): the content's `heightIn(max = 78% of
 * screenHeightDp)` + `verticalScroll` combination needs enough room that the "OK"/"Learn more"
 * actions actually get non-zero layout bounds under Robolectric's default (much smaller) test
 * window — `performClick()` dispatches a real touch at the node's center, so a zero-size node
 * (scrolled out of a too-small viewport) is simply unreachable by click.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1600dp")
class E2eeInfoSheetContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Before
    fun setUp() {
        mockkStatic("com.difft.android.base.utils.ExtensionsKt")
        every { any<android.content.Context>().openExternalBrowser(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic("com.difft.android.base.utils.ExtensionsKt")
    }

    @Test
    fun `T1-1 tapping got-it invokes onDismissRequest exactly once`() {
        var dismissCount = 0
        composeTestRule.setContent {
            DifftTheme {
                E2eeInfoSheetContent(
                    learnMoreUrl = "https://yelling.pro/security",
                    onDismissRequest = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_got_it)).performClick()

        kotlin.test.assertEquals(1, dismissCount)
    }

    @Test
    fun `T1-2 tapping learn-more dismisses then opens browser with exact url`() {
        var dismissCount = 0
        val url = "https://yelling.pro/security"
        composeTestRule.setContent {
            DifftTheme {
                E2eeInfoSheetContent(
                    learnMoreUrl = url,
                    onDismissRequest = { dismissCount++ },
                )
            }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_learn_more)).performClick()

        kotlin.test.assertEquals(1, dismissCount)
        verify(exactly = 1) { any<android.content.Context>().openExternalBrowser(url) }
    }

    @Test
    fun `T1-3 renders all icons title body protected-card items caveat and both actions`() {
        composeTestRule.setContent {
            DifftTheme {
                E2eeInfoSheetContent(
                    learnMoreUrl = "https://yelling.pro/security",
                    onDismissRequest = {},
                )
            }
        }

        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_title)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_body)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_item_message)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_item_call)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_item_file)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_caveat)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_sheet_got_it)).assertExists()
        composeTestRule.onNodeWithText(ctx.getString(R.string.e2ee_learn_more)).assertExists()
    }
}
