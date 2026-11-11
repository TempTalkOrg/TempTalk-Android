package com.difft.android.chat.invite

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import androidx.fragment.app.FragmentActivity
import com.difft.android.chat.R
import com.difft.android.chat.messages.TestScopeApplication
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config








@RunWith(RobolectricTestRunner::class)
@Config(application = TestScopeApplication::class, sdk = [33])
class ScanLayoutViewStateTest {

    private lateinit var controller: ActivityController<FragmentActivity>
    private lateinit var activity: FragmentActivity

    @Before
    fun setUp() {
        controller = Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        runCatching { controller.destroy() }
    }

    
    private fun inflateScan(widthPx: Int, heightPx: Int): ViewGroup {
        val parent = FrameLayout(activity)
        activity.setContentView(parent)
        val root = LayoutInflater.from(activity).inflate(R.layout.activity_scan, parent, false) as ViewGroup
        parent.addView(root)
        root.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        root.layout(0, 0, widthPx, heightPx)
        return root
    }

    
    @Test
    fun `T17 - portrait inflation yields full-bleed preview plus overlay and a top header`() {
        val w = 1080
        val h = 1920
        val root = inflateScan(w, h)

        val preview = root.findViewById<PreviewView>(R.id.previewView)
        assertNotNull("previewView must inflate", preview)
        assertEquals(View.VISIBLE, preview.visibility)
        assertEquals(w, preview.width)
        assertEquals(h, preview.height)

        
        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull("scanOverlay must inflate (proves @JvmOverloads ctor)", overlay)
        assertEquals(w, overlay.width)
        assertEquals(h, overlay.height)

        val back = root.findViewById<View>(R.id.ib_back)
        assertNotNull("ib_back must inflate", back)
        assertEquals(View.VISIBLE, back.visibility)

        val title = root.findViewById<TextView>(R.id.tv_title)
        assertNotNull("tv_title must inflate", title)
        assertEquals(activity.getString(R.string.scan_scan), title.text.toString())
    }

    
    @Test
    @Config(qualifiers = "sw600dp-land")
    fun `T18 - landscape inflation keeps preview full-bleed and overlay centered without distortion`() {
        val w = 1920
        val h = 1080
        val root = inflateScan(w, h)

        val preview = root.findViewById<PreviewView>(R.id.previewView)
        assertNotNull(preview)
        assertEquals(w, preview.width) 
        assertEquals(h, preview.height)

        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull(overlay)
        
        
        assertEquals(w, overlay.width)
        assertEquals(h, overlay.height)

        val back = root.findViewById<View>(R.id.ib_back)
        assertEquals(View.VISIBLE, back.visibility) 
    }

    
    @Test
    @Config(qualifiers = "night")
    fun `T19 - overlay inflates identically under dark mode (no values-night override)`() {
        
        
        val root = inflateScan(1080, 1920)
        val overlay = root.findViewById<ScanFrameOverlayView>(R.id.scanOverlay)
        assertNotNull("overlay inflates under dark config", overlay)
        assertTrue(overlay.width > 0)
    }
}
