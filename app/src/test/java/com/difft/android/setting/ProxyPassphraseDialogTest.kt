package com.difft.android.setting

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.difft.android.R
import com.difft.android.base.ui.theme.DifftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [ProxyPassphraseDialog] contract (issue #1209): confirm is gated on a non-blank passphrase and on
 * no save being in flight, and both the error and the in-flight state stay visible in the content.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ProxyPassphraseDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(resId: Int) = context.getString(resId)

    private val confirmed = mutableListOf<String>()
    private var dismisses = 0

    private fun setDialog(isError: Boolean = false, isSaving: Boolean = false) {
        composeTestRule.setContent {
            DifftTheme(applyWindowBackground = false) {
                ProxyPassphraseDialog(
                    isVisible = true,
                    isError = isError,
                    isSaving = isSaving,
                    onConfirm = { confirmed += it },
                    onDismiss = { dismisses++ },
                )
            }
        }
    }

    // T25
    @Test
    fun `confirm is gated on a non-blank passphrase`() {
        setDialog()

        val confirm = composeTestRule.onNodeWithText(string(R.string.proxy_passphrase_confirm))
        confirm.assertIsNotEnabled()

        composeTestRule.onNode(hasSetTextAction()).performTextInput("secret")
        composeTestRule.waitForIdle()

        confirm.assertIsEnabled()
        confirm.performClick()
        assertEquals(listOf("secret"), confirmed)
    }

    // T26
    @Test
    fun `a wrong passphrase surfaces the error text`() {
        setDialog(isError = true)

        composeTestRule.onNodeWithText(string(R.string.proxy_passphrase_wrong)).assertIsDisplayed()
    }

    // T27
    @Test
    fun `an in-flight save disables confirm and shows the in-flight indicator`() {
        setDialog(isSaving = true)

        composeTestRule.onNodeWithText(string(R.string.proxy_passphrase_confirm)).assertIsNotEnabled()
        composeTestRule.onNodeWithTag(PROXY_PASSPHRASE_IN_FLIGHT_TEST_TAG).assertExists()
    }
}
