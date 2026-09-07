package com.difft.android.base.widget

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The wait-dialog slot is global, so [ComposeDialogManager.dismissWait] with an owner has to key
 * off who showed the dialog: a screen must release its own dialog even once it is finishing, and
 * must never close one another screen showed afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeDialogManagerWaitOwnerTest {

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    @Test
    fun `an owner releases its own dialog while finishing`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount

        ComposeDialogManager.showWait(activity, "")
        assertEquals(baseline + 1, contentRoot.childCount, "the wait dialog must be mounted")

        activity.finish()
        ComposeDialogManager.dismissWait(activity)

        assertEquals(baseline, contentRoot.childCount, "a finishing owner must still release its own dialog")
        controller.destroy()
    }

    @Test
    fun `a dialog shown by another screen is left alone`() {
        val ownerController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val owner = ownerController.get()
        val otherController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val contentRoot = owner.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount

        ComposeDialogManager.showWait(owner, "")
        ComposeDialogManager.dismissWait(otherController.get())

        assertEquals(baseline + 1, contentRoot.childCount, "a non-owner must not close this dialog")

        ComposeDialogManager.dismissWait(owner)

        assertEquals(baseline, contentRoot.childCount, "the owner must still be able to close it")
        ownerController.destroy()
        otherController.destroy()
    }
}
