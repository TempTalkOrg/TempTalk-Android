package com.difft.android.base.widget

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * `showMessageDialog(onClosed)` (#1355): every close reports exactly one reason, `dismiss()` is
 * idempotent, and `onDismiss` runs for an outside tap, back or `dismiss()`, never for the
 * buttons) for the existing callers.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ComposeDialogManagerCloseReasonTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val activity: ComponentActivity get() = composeTestRule.activity
    private val contentRoot: ViewGroup get() = activity.findViewById(android.R.id.content)

    private val reasons = mutableListOf<DialogCloseReason>()
    private var dismissCalls = 0
    private var confirmCalls = 0
    private var cancelCalls = 0

    private fun show(autoDismiss: Boolean = true, cancelable: Boolean = true): ComposeDialog? {
        var dialog: ComposeDialog? = null
        composeTestRule.runOnUiThread {
            dialog = ComposeDialogManager.showMessageDialog(
                context = activity,
                title = "Title",
                confirmText = "Yes",
                cancelText = "No",
                cancelable = cancelable,
                autoDismiss = autoDismiss,
                onConfirm = { confirmCalls++ },
                onCancel = { cancelCalls++ },
                onDismiss = { dismissCalls++ },
                onClosed = { reasons += it },
            )
        }
        composeTestRule.waitForIdle()
        return dialog
    }

    @Test
    fun `confirm reports CONFIRM once and does not call onDismiss`() {
        val baseline = contentRoot.childCount
        show()
        composeTestRule.onNodeWithText("Yes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(DialogCloseReason.CONFIRM), reasons)
        assertEquals(1, confirmCalls)
        assertEquals(0, dismissCalls)
        assertEquals(baseline, contentRoot.childCount)
    }

    @Test
    fun `cancel reports CANCEL once and does not call onDismiss`() {
        show()
        composeTestRule.onNodeWithText("No").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(DialogCloseReason.CANCEL), reasons)
        assertEquals(1, cancelCalls)
        assertEquals(0, dismissCalls)
    }

    @Test
    fun `dismiss reports PROGRAMMATIC once and calls onDismiss once however often it is called`() {
        val baseline = contentRoot.childCount
        val dialog = show()
        assertEquals(baseline + 1, contentRoot.childCount)
        composeTestRule.runOnUiThread {
            dialog?.dismiss()
            dialog?.dismiss()
        }
        assertEquals(listOf(DialogCloseReason.PROGRAMMATIC), reasons)
        assertEquals(1, dismissCalls)
        assertEquals(baseline, contentRoot.childCount)
    }

    @Test
    fun `dismiss after a button close still calls onDismiss as before, with no second reason`() {
        val dialog = show()
        composeTestRule.onNodeWithText("Yes").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.runOnUiThread { dialog?.dismiss() }
        assertEquals(listOf(DialogCloseReason.CONFIRM), reasons)
        assertEquals(1, dismissCalls)
    }

    @Test
    fun `without autoDismiss the buttons keep the dialog and report nothing`() {
        val baseline = contentRoot.childCount
        val dialog = show(autoDismiss = false)
        composeTestRule.onNodeWithText("Yes").performClick()
        composeTestRule.onNodeWithText("Yes").performClick()
        composeTestRule.waitForIdle()
        assertEquals(2, confirmCalls)
        assertTrue(reasons.isEmpty())
        assertEquals(baseline + 1, contentRoot.childCount)
        composeTestRule.runOnUiThread { dialog?.dismiss() }
        assertEquals(listOf(DialogCloseReason.PROGRAMMATIC), reasons)
    }

    @Test
    fun `outside tap or back reports OUTSIDE once with onDismiss once, only when cancelable`() {
        var unmounts = 0
        val closer = MessageDialogCloser(unmount = { unmounts++ }, onDismiss = { dismissCalls++ }, onClosed = { reasons += it })
        closer.outside(cancelable = true)
        closer.outside(cancelable = true)
        closer.dismiss()
        assertEquals(listOf(DialogCloseReason.OUTSIDE), reasons)
        assertEquals(1, dismissCalls)
        assertEquals(1, unmounts)

        val fixed = MessageDialogCloser(unmount = { unmounts++ }, onDismiss = { dismissCalls++ }, onClosed = { reasons += it })
        fixed.outside(cancelable = false)
        assertEquals(listOf(DialogCloseReason.OUTSIDE), reasons)
        assertEquals(1, dismissCalls)
    }

    @Test
    fun `a closed dialog ignores further button taps`() {
        val closer = MessageDialogCloser(unmount = {}, onDismiss = {}, onClosed = { reasons += it })
        closer.confirm(autoDismiss = true) { confirmCalls++ }
        closer.confirm(autoDismiss = true) { confirmCalls++ }
        closer.cancel(autoDismiss = true) { cancelCalls++ }
        assertEquals(1, confirmCalls)
        assertEquals(0, cancelCalls)
        assertEquals(listOf(DialogCloseReason.CONFIRM), reasons)
    }
}
