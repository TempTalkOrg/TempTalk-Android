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

        
        ComposeDialogManager.dismissWait(first)
        assertEquals(secondBaseline + 1, secondRoot.childCount)

        firstController.destroy()
        secondController.destroy()
    }

    




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

    



    @Test
    fun `a destroyed owner clears the slot`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()

        val token = ComposeDialogManager.showWait(activity, "")
        assertTrue(ComposeDialogManager.isWaitShowing(token))

        controller.destroy()

        assertFalse(ComposeDialogManager.isWaitShowing(token), "the slot must not outlive its owner")
    }

    
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
