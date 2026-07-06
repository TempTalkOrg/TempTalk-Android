package com.difft.android.chat.util

import android.content.ContextWrapper
import android.view.View
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Unit coverage for the shared [View.isHostActivityAlive] extension declared in
 * `QuoteThumbnailBinder.kt`. The extension walks the [ContextWrapper] chain to the host Activity and
 * reports whether it is still alive — the dead-Activity guard relied on by both the list ViewHolder
 * (⑤) and the input compose-bar (⑥) before any `Glide.with(view)` call.
 *
 * `isHostActivityAlive()` is pure (no native WCDB / Hilt dependency), so the finishing / destroyed /
 * non-Activity-ContextWrapper matrix is fully runnable under Robolectric (no @Ignore needed).
 *
 * Verify: :chat:testDebugUnitTest --tests "*QuoteThumbnailBinderTest*"
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class QuoteThumbnailBinderTest {

    private var controller: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    /** A View whose context is a live, resumed Activity → host is alive. */
    @Test
    fun `isHostActivityAlive returns true for a live activity`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)

        assertTrue(view.isHostActivityAlive())
    }

    /** A finishing Activity (finish() called) → host is NOT alive. */
    @Test
    fun `isHostActivityAlive returns false when host activity is finishing`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)
        activity.finish()

        assertFalse(view.isHostActivityAlive())
    }

    /** A destroyed Activity → host is NOT alive. */
    @Test
    fun `isHostActivityAlive returns false when host activity is destroyed`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)
        controller!!.pause().stop().destroy()

        assertFalse(view.isHostActivityAlive())
    }

    /**
     * A View whose context is a non-Activity [ContextWrapper] chain (no Activity reachable) → the
     * extension assumes safe and returns true (there is nothing Activity-scoped to be torn down).
     */
    @Test
    fun `isHostActivityAlive returns true for a non-activity contextwrapper chain`() {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        // Nested ContextWrapper that never resolves to an Activity.
        val wrapped = ContextWrapper(ContextWrapper(appContext))
        val view = View(wrapped)

        assertTrue(view.isHostActivityAlive())
    }

    /**
     * The Activity is reachable through a wrapping [ContextWrapper] (mirrors Hilt's
     * FragmentContextWrapper) → the extension unwraps to it and reports its alive state.
     */
    @Test
    fun `isHostActivityAlive unwraps a wrapping contextwrapper to the host activity`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val wrappedView = View(ContextWrapper(activity))

        assertTrue(wrappedView.isHostActivityAlive())

        controller!!.pause().stop().destroy()
        assertFalse(wrappedView.isHostActivityAlive())
    }
}
