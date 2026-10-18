package com.difft.android.base.widget

import android.app.Activity
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.findViewTreeLifecycleOwner
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T1-10: framework-assumption pin for [ComposeActivityMount] — the shared mount/unmount
 * mechanism [E2eeInfoSheet.show] and [ComposeDialogManager] both rely on. Verifies the
 * ComposeView is actually attached to
 * `android.R.id.content` (and detached again) and that ViewTree owners are installed so
 * the hosted Compose content can actually recompose. Also pins that a plain Activity host
 * is skipped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ComposeActivityMountTest {

    @Test
    fun `T1-10 mount attaches to content root with lifecycle owner, unmount detaches`() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val composeView = ComposeView(activity).apply {
            setContent { TrivialContent() }
        }
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

        ComposeActivityMount.mount(activity, composeView)

        assertTrue(rootView.containsDescendant(composeView), "composeView must be attached under content root after mount")
        assertNotNull(composeView.findViewTreeLifecycleOwner(), "ViewTreeLifecycleOwner must be installed after mount")

        ComposeActivityMount.unmount(activity, composeView)

        assertFalse(rootView.containsDescendant(composeView), "composeView must be detached from content root after unmount")

        controller.destroy()
    }

    @Test
    fun `mount on a plain Activity host is a no-op and unmount stays safe`() {
        // A plain Activity has no ViewTree owners, so mounting would crash on first attach.
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val composeView = ComposeView(activity).apply {
            setContent { TrivialContent() }
        }
        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

        ComposeActivityMount.mount(activity, composeView)

        assertFalse(rootView.containsDescendant(composeView), "composeView must not be attached on a host without ViewTree owners")

        ComposeActivityMount.unmount(activity, composeView)

        controller.destroy()
    }

    private fun ViewGroup.containsDescendant(target: android.view.View): Boolean {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child === target) return true
            if (child is ViewGroup && child.containsDescendant(target)) return true
        }
        return false
    }
}

@Composable
private fun TrivialContent() {
    Box {}
}
