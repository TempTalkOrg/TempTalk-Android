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












@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class QuoteThumbnailBinderTest {

    private var controller: ActivityController<FragmentActivity>? = null

    @After
    fun tearDown() {
        runCatching { controller?.destroy() }
    }

    
    @Test
    fun `isHostActivityAlive returns true for a live activity`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)

        assertTrue(view.isHostActivityAlive())
    }

    
    @Test
    fun `isHostActivityAlive returns false when host activity is finishing`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)
        activity.finish()

        assertFalse(view.isHostActivityAlive())
    }

    
    @Test
    fun `isHostActivityAlive returns false when host activity is destroyed`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val view = View(activity)
        controller!!.pause().stop().destroy()

        assertFalse(view.isHostActivityAlive())
    }

    



    @Test
    fun `isHostActivityAlive returns true for a non-activity contextwrapper chain`() {
        val appContext = org.robolectric.RuntimeEnvironment.getApplication()
        
        val wrapped = ContextWrapper(ContextWrapper(appContext))
        val view = View(wrapped)

        assertTrue(view.isHostActivityAlive())
    }

    



    @Test
    fun `isHostActivityAlive unwraps a wrapping contextwrapper to the host activity`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val wrappedView = View(ContextWrapper(activity))

        assertTrue(wrappedView.isHostActivityAlive())

        controller!!.pause().stop().destroy()
        assertFalse(wrappedView.isHostActivityAlive())
    }

    
    @Test
    fun `loadRoundedThumbnail clips to its own corners only when rounded`() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).setup()
        val activity = controller!!.get()
        val rounded = android.widget.ImageView(activity)
        val flush = android.widget.ImageView(activity)

        QuoteThumbnailBinder.loadRoundedThumbnail(rounded, ByteArray(0))
        QuoteThumbnailBinder.loadRoundedThumbnail(flush, ByteArray(0), rounded = false)

        assertTrue(rounded.clipToOutline)
        assertFalse(flush.clipToOutline)
    }
}
