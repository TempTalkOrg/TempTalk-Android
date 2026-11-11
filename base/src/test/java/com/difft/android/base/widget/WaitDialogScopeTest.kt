package com.difft.android.base.widget

import android.os.Looper
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode








@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WaitDialogScopeTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun tearDown() = ComposeDialogManager.dismissWait()

    private val activity: ComponentActivity get() = composeTestRule.activity

    private val contentRoot: ViewGroup get() = activity.findViewById(android.R.id.content)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    

    @Test
    fun `withWait shows while the block runs and releases on return`() {
        val baseline = contentRoot.childCount
        var childCountInsideBlock = -1

        val result = runBlocking {
            activity.withWait {
                childCountInsideBlock = contentRoot.childCount
                42
            }
        }

        assertEquals(42, result)
        assertEquals("the dialog must be up for the whole block", baseline + 1, childCountInsideBlock)
        assertEquals("and released on return", baseline, contentRoot.childCount)
    }

    @Test
    fun `withWait releases when the block throws and rethrows`() {
        val baseline = contentRoot.childCount

        assertThrows(IllegalStateException::class.java) {
            runBlocking { activity.withWait { throw IllegalStateException("boom") } }
        }

        assertEquals(baseline, contentRoot.childCount)
    }

    @Test
    fun `withWait releases when the scope is cancelled`() {
        val baseline = contentRoot.childCount
        val scope = CoroutineScope(Dispatchers.Main.immediate)

        val job = scope.launch { activity.withWait { awaitCancellation() } }
        idle()
        assertEquals("the dialog must be up while the request is in flight", baseline + 1, contentRoot.childCount)

        job.cancel()
        idle()

        assertEquals("a cancelled scope must still release the modal", baseline, contentRoot.childCount)
    }

    

    @Test
    fun `withWait leaves a dialog another screen owns alone and releases its own`() {
        val baseline = contentRoot.childCount
        val other = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        var childCountAfterOtherDismiss = -1

        runBlocking {
            activity.withWait {
                ComposeDialogManager.dismissWait(other)
                childCountAfterOtherDismiss = contentRoot.childCount
            }
        }

        assertEquals("a non-owner must not close this dialog", baseline + 1, childCountAfterOtherDismiss)
        assertEquals(baseline, contentRoot.childCount)
    }

    @Test
    fun `a finishing owner still releases its dialog`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val owner = controller.get()
        val ownerRoot = owner.findViewById<ViewGroup>(android.R.id.content)
        val baseline = ownerRoot.childCount

        runBlocking { owner.withWait { owner.finish() } }

        assertEquals(baseline, ownerRoot.childCount)
        controller.destroy()
    }

    





    @Test
    fun `showing a message dialog does not release the wait dialog`() {
        val baseline = contentRoot.childCount

        WaitDialogHandle(activity).show()
        val message = ComposeDialogManager.showMessageDialog(
            context = activity,
            title = "Different account",
            confirmText = "OK",
            cancelText = "Cancel",
        )
        message?.dismiss()

        assertEquals("the wait dialog survives the message dialog", baseline + 1, contentRoot.childCount)
    }

    

    




    @Test
    fun `a handle shows once and releasing twice is harmless`() {
        val baseline = contentRoot.childCount
        val handle = WaitDialogHandle(activity)

        handle.show()
        assertEquals(baseline + 1, contentRoot.childCount)
        assertTrue(handle.isShowing)
        val window = contentRoot.getChildAt(baseline)
        handle.show()
        assertEquals("a repeat show must not stack dialogs", baseline + 1, contentRoot.childCount)
        assertSame("nor rebuild the window", window, contentRoot.getChildAt(baseline))

        handle.dismiss()
        assertEquals(baseline, contentRoot.childCount)
        assertFalse(handle.isShowing)
        handle.dismiss()
        assertEquals(baseline, contentRoot.childCount)
    }

    






    @Test
    fun `two flows on one screen share the window until the last one releases`() {
        val baseline = contentRoot.childCount
        val first = WaitDialogHandle(activity)
        val second = WaitDialogHandle(activity)

        first.show()
        val window = contentRoot.getChildAt(baseline)
        second.show()

        assertEquals("one window, not two", baseline + 1, contentRoot.childCount)
        assertSame("and the live one, not a rebuild", window, contentRoot.getChildAt(baseline))
        assertTrue(first.isShowing)
        assertTrue(second.isShowing)

        first.dismiss()
        assertEquals(
            "a flow still in flight keeps the window",
            baseline + 1,
            contentRoot.childCount
        )
        assertFalse("the released claim is gone", first.isShowing)
        assertTrue(second.isShowing)

        second.dismiss()
        assertEquals("the last release closes it", baseline, contentRoot.childCount)
        assertFalse(second.isShowing)
    }

    
    @Test
    fun `a blanket release drops every claim on the window`() {
        val baseline = contentRoot.childCount
        val first = WaitDialogHandle(activity)
        val second = WaitDialogHandle(activity)

        first.show()
        second.show()
        ComposeDialogManager.dismissWait()

        assertEquals(baseline, contentRoot.childCount)
        assertFalse(first.isShowing)
        assertFalse(second.isShowing)
    }

    



    @Test
    fun `destroying the bound lifecycle releases the handle's claim`() {
        val baseline = contentRoot.childCount
        val viewOwner = FakeLifecycleOwner()
        val handle = WaitDialogHandle(activity, viewOwner.lifecycle)

        handle.show()
        assertEquals(baseline + 1, contentRoot.childCount)
        assertTrue(handle.isShowing)

        viewOwner.registry.currentState = Lifecycle.State.DESTROYED

        assertEquals("a destroyed lifecycle must release the modal", baseline, contentRoot.childCount)
        assertFalse(handle.isShowing)
    }

    
    private class FakeLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    

    @Test
    fun `an invisible WaitDialogEffect leaves another flow's dialog alone`() {
        val beforeContent = contentRoot.childCount

        var mounted by mutableStateOf(true)
        composeTestRule.setContent { if (mounted) WaitDialogEffect(visible = false) }
        composeTestRule.waitForIdle()
        
        val baseline = contentRoot.childCount
        assertEquals(beforeContent + 1, baseline)

        val other = WaitDialogHandle(activity)
        other.show()
        mounted = false
        composeTestRule.waitForIdle()

        assertEquals("disposing invisible must close nothing", baseline + 1, contentRoot.childCount)
        assertTrue(other.isShowing)
    }

    @Test
    fun `WaitDialogEffect follows the visible flag`() {
        var visible by mutableStateOf(false)
        composeTestRule.setContent { WaitDialogEffect(visible) }
        composeTestRule.waitForIdle()
        
        val baseline = contentRoot.childCount

        visible = true
        composeTestRule.waitForIdle()
        assertEquals(baseline + 1, contentRoot.childCount)

        visible = false
        composeTestRule.waitForIdle()
        assertEquals(baseline, contentRoot.childCount)
    }

    @Test
    fun `WaitDialogEffect releases when it leaves the composition while still visible`() {
        
        
        var mounted by mutableStateOf(false)
        composeTestRule.setContent { if (mounted) WaitDialogEffect(visible = true) }
        composeTestRule.waitForIdle()
        val baseline = contentRoot.childCount

        mounted = true
        composeTestRule.waitForIdle()
        assertEquals(baseline + 1, contentRoot.childCount)

        mounted = false
        composeTestRule.waitForIdle()

        assertEquals(baseline, contentRoot.childCount)
    }
}
