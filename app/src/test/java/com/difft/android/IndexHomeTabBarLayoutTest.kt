package com.difft.android

import android.os.Looper
import androidx.activity.ComponentActivity
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config








@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class IndexHomeTabBarLayoutTest {

    
    private fun inflate(): View {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContentView(R.layout.activity_index)
        controller.visible()
        shadowOf(Looper.getMainLooper()).idle()
        return activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
    }

    private fun assertCommonStructure(root: View) {
        val listContent = root.findViewById<ViewGroup>(R.id.list_content)
        val pager = root.findViewById<View>(R.id.index_viewpager)
        val host = root.findViewById<ViewGroup>(R.id.home_tab_bar)
        assertNotNull("home_tab_bar must exist", host)
        assertNotNull("home_tab_capsule ComposeView must exist", root.findViewById<View>(R.id.home_tab_capsule))
        assertTrue("pager and capsule host share list_content", pager.parent === listContent && host.parent === listContent)
        assertTrue("capsule host is declared after the pager (drawn on top)", listContent.indexOfChild(host) > listContent.indexOfChild(pager))
        assertFalse("shadow must draw into the bottom padding", host.clipToPadding)
        assertFalse(host.clipChildren)
        assertEquals("pager reaches the bottom of its container", listContent.height, pager.bottom)
        assertEquals("capsule host hugs the bottom of its container", listContent.height, host.bottom)
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `phone tree - capsule host over the pager, under the search and detail layers`() {
        val root = inflate() as ViewGroup
        assertCommonStructure(root)
        val listContent = root.findViewById<View>(R.id.list_content)
        assertTrue("list_content fills the root bottom (no fixed bar below it)", listContent.bottom == root.height)
        val search = root.findViewById<View>(R.id.list_layer)
        val detail = root.findViewById<View>(R.id.detail_pane)
        assertTrue(root.indexOfChild(search) > root.indexOfChild(listContent))
        assertTrue(root.indexOfChild(detail) > root.indexOfChild(search))
    }

    @Test
    @Config(qualifiers = "w900dp-h700dp")
    fun `two-pane tree - capsule host lives in the list pane, under its search layer`() {
        val root = inflate() as ViewGroup
        assertCommonStructure(root)
        val listPane = root.findViewById<ViewGroup>(R.id.list_pane)
        val listContent = root.findViewById<View>(R.id.list_content)
        assertTrue("list_content is the list pane's content block", listContent.parent === listPane)
        val search = root.findViewById<View>(R.id.list_layer)
        assertTrue("search layer covers the capsule inside the list pane", search.parent === listPane)
        assertTrue(listPane.indexOfChild(search) > listPane.indexOfChild(listContent))
        assertEquals(
            "list_pane carries the dimens band it was inflated under",
            root.resources.getString(R.string.dual_pane_band),
            listPane.tag,
        )
    }
}
