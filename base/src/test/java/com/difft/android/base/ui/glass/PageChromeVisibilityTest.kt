package com.difft.android.base.ui.glass

import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.flow.MutableStateFlow


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PageChromeVisibilityTest {
    private class Owner(state: Lifecycle.State) : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = state }
        override val lifecycle: Lifecycle get() = registry
        fun move(state: Lifecycle.State) { registry.currentState = state }
    }

    private val controller = Robolectric.buildActivity(FragmentActivity::class.java).setup().visible()
    private val activity = controller.get()
    private val header = FrameLayout(activity)
    private val button = View(activity)
    private val absent = View(activity).apply { visibility = View.GONE }
    private var binding: AutoCloseable? = null

    init {
        header.addView(button, FrameLayout.LayoutParams(44, 52))
        activity.setContentView(FrameLayout(activity).apply {
            addView(header, FrameLayout.LayoutParams(400, 52))
            addView(absent, FrameLayout.LayoutParams(400, 20))
            measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            layout(0, 0, 400, 800)
        })
    }

    @After
    fun tearDown() {
        binding?.close()
        controller.pause().stop().destroy()
    }

    @Test
    fun `last-front page retains chrome while the window pauses but coverage still takes precedence`() {
        val window = Owner(Lifecycle.State.RESUMED)
        val page = Owner(Lifecycle.State.RESUMED)
        val covered = MutableStateFlow(false)
        binding = bindPageChromeVisibility(page, window.lifecycle, covered, header, absent)
        assertTrue(button.isShown)
        window.move(Lifecycle.State.STARTED)
        page.move(Lifecycle.State.STARTED)
        assertTrue("a dialog or recents must retain the last front page", button.isShown)
        assertEquals(52, header.height)
        covered.value = true
        ShadowLooper.idleMainLooper()
        assertFalse(button.isShown)
        assertEquals(View.INVISIBLE, header.visibility)
        assertEquals(View.GONE, absent.visibility)
        assertEquals(52, header.height)
        covered.value = false
        ShadowLooper.idleMainLooper()
        assertTrue(button.isShown)
        page.move(Lifecycle.State.CREATED)
        assertFalse("stopped pages never count as front", button.isShown)
    }

    @Test
    fun `a hidden tab stays hidden when the whole window later pauses`() {
        val window = Owner(Lifecycle.State.RESUMED)
        val page = Owner(Lifecycle.State.RESUMED)
        binding = bindPageChromeVisibility(page, window.lifecycle, null, header)
        page.move(Lifecycle.State.STARTED)
        assertFalse(button.isShown)
        window.move(Lifecycle.State.STARTED)
        assertFalse("window pause cannot revive the previously hidden Chats tab", button.isShown)
        window.move(Lifecycle.State.RESUMED)
        page.move(Lifecycle.State.RESUMED)
        assertTrue(button.isShown)
    }

    @Test
    fun `a page that never resumed stays hidden and disposal restores original visibility without observers`() {
        val window = Owner(Lifecycle.State.STARTED)
        val page = Owner(Lifecycle.State.STARTED)
        val covered = MutableStateFlow(true)
        binding = bindPageChromeVisibility(page, window.lifecycle, covered, header, absent)
        assertFalse(button.isShown)
        covered.value = false
        ShadowLooper.idleMainLooper()
        assertFalse("a prefetched pager neighbour is not the last front page", button.isShown)
        window.move(Lifecycle.State.RESUMED)
        page.move(Lifecycle.State.RESUMED)
        assertTrue(button.isShown)
        page.move(Lifecycle.State.DESTROYED)
        assertEquals(View.VISIBLE, header.visibility)
        assertEquals(View.GONE, absent.visibility)
        header.visibility = View.GONE
        covered.value = true
        window.move(Lifecycle.State.STARTED)
        ShadowLooper.idleMainLooper()
        assertEquals("disposed observers and collectors must leave the view alone", View.GONE, header.visibility)
    }
}
