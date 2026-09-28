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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The wait-dialog slot is global, so [ComposeDialogManager.dismissWait] with an owner has to key
 * off who showed the dialog: a screen must release its own dialog even once it is finishing, and
 * must never close one another screen showed afterwards. Within one screen the claims share that
 * dialog, reference counted, so it closes only once the last of them is released.
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

    /**
     * The boundary of the "a wait dialog guards its own screen" invariant (issue #1208): the slot
     * is global, so a second screen's `showWait` evicts the first screen's dialog while its request
     * is still in flight. Pinned here because that is the one way a screen can lose its modal
     * without its own code releasing it, and it is why `showWait` logs the eviction.
     */
    @Test
    fun `a second screen showing a wait dialog evicts the first screen's`() {
        val firstController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val first = firstController.get()
        val secondController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = secondController.get()
        val firstRoot = first.findViewById<ViewGroup>(android.R.id.content)
        val secondRoot = second.findViewById<ViewGroup>(android.R.id.content)
        val firstBaseline = firstRoot.childCount
        val secondBaseline = secondRoot.childCount

        ComposeDialogManager.showWait(first, "")
        ComposeDialogManager.showWait(second, "")

        assertEquals(firstBaseline, firstRoot.childCount, "the first screen's dialog is evicted")
        assertEquals(secondBaseline + 1, secondRoot.childCount, "the second screen now owns the slot")

        // And the first screen releasing afterwards must not take the second screen's dialog down.
        ComposeDialogManager.dismissWait(first)
        assertEquals(secondBaseline + 1, secondRoot.childCount)

        firstController.destroy()
        secondController.destroy()
    }

    /**
     * Token granularity, which is finer than the owner: one screen runs several flows through the
     * one global slot, so its claims share the window — reference counted, so the dialog stands
     * until the last one is released and neither flow can pull it out from under the other.
     */
    @Test
    fun `same-screen claims share the dialog until the last release`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount

        val first = ComposeDialogManager.showWait(activity, "")
        val second = ComposeDialogManager.showWait(activity, "")
        assertTrue(second != first, "each show hands out a fresh claim")
        assertTrue(ComposeDialogManager.isWaitShowing(first), "both claims hold the same window")
        assertTrue(ComposeDialogManager.isWaitShowing(second))

        ComposeDialogManager.dismissWait(first)
        assertEquals(baseline + 1, contentRoot.childCount, "a claim still held keeps the dialog up")
        assertFalse(ComposeDialogManager.isWaitShowing(first), "the released claim is gone")
        assertTrue(ComposeDialogManager.isWaitShowing(second))

        ComposeDialogManager.dismissWait(second)
        assertEquals(baseline, contentRoot.childCount, "the last release closes it")
        assertFalse(ComposeDialogManager.isWaitShowing(second))
        controller.destroy()
    }

    /** A claim whose dialog another screen evicted releases nothing. */
    @Test
    fun `a claim evicted by another screen releases nothing`() {
        val firstController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val secondController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = secondController.get()
        val secondRoot = second.findViewById<ViewGroup>(android.R.id.content)
        val secondBaseline = secondRoot.childCount

        val stale = ComposeDialogManager.showWait(firstController.get(), "")
        val live = ComposeDialogManager.showWait(second, "")
        assertFalse(ComposeDialogManager.isWaitShowing(stale), "the evicted claim is no longer live")

        ComposeDialogManager.dismissWait(stale)

        assertEquals(secondBaseline + 1, secondRoot.childCount, "a stale token must not release the dialog")
        assertTrue(ComposeDialogManager.isWaitShowing(live))
        firstController.destroy()
        secondController.destroy()
    }

    /** The blanket release closes the window and drops every claim on it. */
    @Test
    fun `a blanket release drops every claim on the window`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount

        val first = ComposeDialogManager.showWait(activity, "")
        val second = ComposeDialogManager.showWait(activity, "")

        ComposeDialogManager.dismissWait()

        assertEquals(baseline, contentRoot.childCount)
        assertFalse(ComposeDialogManager.isWaitShowing(first))
        assertFalse(ComposeDialogManager.isWaitShowing(second))
        controller.destroy()
    }

    /**
     * A same-screen re-show joins the live window instead of rebuilding it, so the modal cannot
     * blink between two flows on one screen. The first claim's message and flags therefore stay —
     * the slot takes no view on who asked, and re-mounting for a later caller's text would flash
     * the live modal off and on.
     */
    @Test
    fun `a same-screen re-show keeps the live window`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val contentRoot = activity.findViewById<ViewGroup>(android.R.id.content)
        val baseline = contentRoot.childCount

        ComposeDialogManager.showWait(activity, "")
        val window = contentRoot.getChildAt(baseline)
        ComposeDialogManager.showWait(activity, "")

        assertEquals(baseline + 1, contentRoot.childCount, "one dialog, not two")
        assertSame(window, contentRoot.getChildAt(baseline), "the window is not rebuilt")
        controller.destroy()
    }

    /**
     * The owner's destruction clears the slot, so a screen that never released its non-cancelable
     * modal cannot leave a closure over a dead Activity in a process-lifetime object.
     */
    @Test
    fun `a destroyed owner clears the slot`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        val token = ComposeDialogManager.showWait(activity, "")
        assertTrue(ComposeDialogManager.isWaitShowing(token))

        controller.destroy()

        assertFalse(ComposeDialogManager.isWaitShowing(token), "the slot must not outlive its owner")
    }

    /** And a screen destroyed after another one took the slot over must not close that dialog. */
    @Test
    fun `a destroyed owner leaves the dialog another screen now owns alone`() {
        val firstController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val secondController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val second = secondController.get()
        val secondRoot = second.findViewById<ViewGroup>(android.R.id.content)
        val secondBaseline = secondRoot.childCount

        ComposeDialogManager.showWait(firstController.get(), "")
        val token = ComposeDialogManager.showWait(second, "")

        firstController.destroy()

        assertTrue(ComposeDialogManager.isWaitShowing(token), "the live claim survives")
        assertEquals(secondBaseline + 1, secondRoot.childCount)
        secondController.destroy()
    }
}
