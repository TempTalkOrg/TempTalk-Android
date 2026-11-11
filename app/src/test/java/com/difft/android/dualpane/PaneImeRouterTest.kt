package com.difft.android.dualpane

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.view.animation.LinearInterpolator
import android.widget.EditText
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit






@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class PaneImeRouterTest {

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val root: View get() = host.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)

    
    private val received = mutableMapOf<String, Int>()

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun launch(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        settle()
    }

    private fun probe(name: String, id: Int) {
        ViewCompat.setOnApplyWindowInsetsListener(host.findViewById(id)) { _, insets ->
            received[name] = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            insets
        }
    }

    private fun dispatchIme(bottom: Int): WindowInsetsCompat {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.of(0, 60, 0, 40))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
            .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
            .build()
        ViewCompat.dispatchApplyWindowInsets(root, insets)
        return insets
    }

    private fun focusIn(paneId: Int): EditText {
        val field = EditText(host).also { it.id = View.generateViewId() }
        host.findViewById<ViewGroup>(paneId).addView(field, ViewGroup.LayoutParams(100, 100))
        assertTrue(field.requestFocus())
        settle()
        return field
    }

    



    private fun noFocus() {
        host.findViewById<ViewGroup>(R.id.index_viewpager).getChildAt(0).isFocusable = false
        root.findFocus()?.clearFocus()
        settle()
        assertNull(requireNotNull(host.paneImeRouter).paneOf(root.findFocus()))
    }

    private fun removeField(field: EditText) {
        (field.parent as ViewGroup).removeView(field)
        settle()
    }

    @Test
    fun `two-pane - only the pane holding the focus receives the keyboard inset`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        noFocus()

        dispatchIme(500)
        assertEquals("no focus: both panes see the keyboard", mapOf("list" to 500, "detail" to 500), received)

        val searchField = focusIn(R.id.list_pane)
        assertSame(host.findViewById(R.id.list_pane), router.owner)
        dispatchIme(500)
        assertEquals("the search field's keyboard stays in the list pane", mapOf("list" to 500, "detail" to 0), received)

        val chatInput = focusIn(R.id.detail_pane)
        assertSame("focus moved to the conversation input", host.findViewById(R.id.detail_pane), router.owner)
        dispatchIme(500)
        assertEquals(mapOf("list" to 0, "detail" to 500), received)

        removeField(chatInput) 
        assertSame(host.findViewById(R.id.list_pane), router.owner)
        dispatchIme(500)
        removeField(searchField)
        assertNull("focus left both panes", router.paneOf(root.findFocus()))
        assertSame("the keyboard is still up: its owner is kept", host.findViewById(R.id.list_pane), router.owner)
        dispatchIme(0)
        assertNull("keyboard hidden and no editor focused: nothing to route", router.owner)
        dispatchIme(500)
        assertEquals("a keyboard with no editor anywhere: both panes get the real insets", mapOf("list" to 500, "detail" to 500), received)
    }

    @Test
    fun `two-pane - system bars still reach the non-owning pane`() {
        launch("w840dp-h900dp")
        var detailBars = -1
        ViewCompat.setOnApplyWindowInsetsListener(host.findViewById(R.id.fragment_container_detail)) { _, insets ->
            detailBars = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            insets
        }
        focusIn(R.id.list_pane)

        dispatchIme(500)

        assertEquals(40, detailBars)
    }

    @Test
    fun `a focus change re-dispatches the window insets by itself`() {
        launch("w840dp-h900dp")
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        focusIn(R.id.list_pane)
        dispatchIme(500)
        assertEquals(mapOf("list" to 500, "detail" to 0), received)
        received.clear()

        focusIn(R.id.detail_pane) 
        settle()

        assertEquals("both panes received a fresh dispatch without anyone asking", setOf("list", "detail"), received.keys)
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 - the apply routing works without animation callbacks`() {
        launch("w840dp-h900dp")
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        
        
        noFocus()
        dispatchIme(500)
        val unrouted = received.getValue("list")
        assertTrue("the fake keyboard reaches the panes", unrouted > 0)
        assertEquals(unrouted, received["detail"])

        focusIn(R.id.list_pane)
        dispatchIme(500)

        assertEquals(mapOf("list" to unrouted, "detail" to 0), received)
    }

    
    private class EndProbe : WindowInsetsAnimationCompat.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        var prepares = 0
        var ends = 0
        override fun onPrepare(animation: WindowInsetsAnimationCompat) { prepares++ }
        override fun onProgress(insets: WindowInsetsCompat, runningAnimations: List<WindowInsetsAnimationCompat>): WindowInsetsCompat = insets
        override fun onEnd(animation: WindowInsetsAnimationCompat) { ends++ }
    }

    private fun imeAnimation() = WindowInsetsAnimation(WindowInsets.Type.ime(), LinearInterpolator(), 300L)

    private fun runImeAnimation(animation: WindowInsetsAnimation, between: () -> Unit = {}) {
        root.dispatchWindowInsetsAnimationPrepare(animation)
        root.dispatchWindowInsetsAnimationStart(animation, WindowInsetsAnimation.Bounds(Insets.NONE.toPlatformInsets(), Insets.of(0, 0, 0, 500).toPlatformInsets()))
        between()
        root.dispatchWindowInsetsAnimationEnd(animation)
    }

    @Test
    fun `a focus switch during an IME animation leaves every page its onEnd and swaps the pane modes afterwards`() {
        launch("w840dp-h900dp")
        val listProbe = EndProbe()
        val detailProbe = EndProbe()
        ViewCompat.setWindowInsetsAnimationCallback(host.findViewById(R.id.index_viewpager), listProbe)
        ViewCompat.setWindowInsetsAnimationCallback(host.findViewById(R.id.fragment_container_detail), detailProbe)
        focusIn(R.id.detail_pane) 

        
        runImeAnimation(imeAnimation()) { focusIn(R.id.list_pane) }

        assertEquals("the detail page sees the end of the animation it started with", 1, detailProbe.ends)
        assertEquals("the list was STOP for this animation", 0, listProbe.prepares)
        settle()

        
        runImeAnimation(imeAnimation())
        assertEquals(1, listProbe.prepares)
        assertEquals(1, listProbe.ends)
        assertEquals("no second prepare/end for the detail page", 1, detailProbe.ends)
        assertEquals(1, detailProbe.prepares)
    }

    
    private class LateEditor(context: android.content.Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
        }
        override fun onCheckIsTextEditor(): Boolean = false
    }

    @Test
    fun `a keyboard opening for a focused view that is no text editor yet still routes to its pane`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        val listProbe = EndProbe()
        val detailProbe = EndProbe()
        ViewCompat.setWindowInsetsAnimationCallback(host.findViewById(R.id.index_viewpager), listProbe)
        ViewCompat.setWindowInsetsAnimationCallback(host.findViewById(R.id.fragment_container_detail), detailProbe)
        noFocus()
        val field = LateEditor(host)
        host.findViewById<ViewGroup>(R.id.list_pane).addView(field, ViewGroup.LayoutParams(100, 100))
        assertTrue(field.requestFocus())
        settle()
        assertNull("a focus change alone routes nothing for a non-editor with the keyboard hidden", router.owner)

        
        runImeAnimation(imeAnimation()) { dispatchIme(500) }

        assertSame(host.findViewById(R.id.list_pane), router.owner)
        assertEquals("the owner's page animates", 1, listProbe.prepares)
        assertEquals("the other pane is shielded for this animation", 0, detailProbe.prepares)
        assertEquals(mapOf("list" to 500, "detail" to 0), received)
    }

    @Test
    fun `closing search while the keyboard is up keeps the list as owner through the hide, then releases it`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        val detailProbe = EndProbe()
        ViewCompat.setWindowInsetsAnimationCallback(host.findViewById(R.id.fragment_container_detail), detailProbe)
        noFocus()
        val searchField = focusIn(R.id.list_pane)
        dispatchIme(500)
        assertSame(host.findViewById(R.id.list_pane), router.owner)

        
        removeField(searchField)
        assertSame("focus left the field but the keyboard is still up", host.findViewById(R.id.list_pane), router.owner)
        runImeAnimation(imeAnimation()) {
            assertSame("no recompute for a hide", host.findViewById(R.id.list_pane), router.owner)
            dispatchIme(0) 
        }
        assertEquals("the conversation pane saw no hide animation", 0, detailProbe.prepares)
        assertEquals(0, received["detail"])
        settle()

        assertNull("hidden, and the focused view is no editor", router.owner)
        dispatchIme(500)
        assertEquals(mapOf("list" to 500, "detail" to 500), received)
    }

    @Test
    fun `with the keyboard up, focus moving into the other pane hands it over before the view is an editor`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        noFocus()
        focusIn(R.id.detail_pane) 
        dispatchIme(500)
        assertSame(host.findViewById(R.id.detail_pane), router.owner)

        
        val field = LateEditor(host)
        host.findViewById<ViewGroup>(R.id.list_pane).addView(field, ViewGroup.LayoutParams(100, 100))
        assertTrue(field.requestFocus())
        settle()

        assertSame(host.findViewById(R.id.list_pane), router.owner)
        dispatchIme(500)
        assertEquals(mapOf("list" to 500, "detail" to 0), received)
    }

    @Test
    fun `with the keyboard up, the fallback focus after the chat input left the tree keeps the owner`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        probe("list", R.id.index_viewpager)
        probe("detail", R.id.fragment_container_detail)
        noFocus()
        
        host.findViewById<ViewGroup>(R.id.list_pane).addView(LateEditor(host), ViewGroup.LayoutParams(100, 100))
        val chatInput = focusIn(R.id.detail_pane)
        dispatchIme(500)
        assertSame(host.findViewById(R.id.detail_pane), router.owner)

        
        removeField(chatInput)
        assertSame(host.findViewById(R.id.list_pane), router.paneOf(root.findFocus()))

        assertSame("not a hand-over: the keyboard still belongs to the conversation", host.findViewById(R.id.detail_pane), router.owner)
        dispatchIme(500)
        assertEquals(mapOf("list" to 0, "detail" to 500), received)
    }

    @Test
    fun `with the keyboard up, focus leaving both panes keeps the owner`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        noFocus()
        val field = focusIn(R.id.detail_pane)
        dispatchIme(500)

        removeField(field) 
        assertNull(router.paneOf(root.findFocus()))

        assertSame(host.findViewById(R.id.detail_pane), router.owner)
    }

    @Test
    fun `a text editor focused in the other pane mid-hide takes the keyboard over`() {
        launch("w840dp-h900dp")
        val router = requireNotNull(host.paneImeRouter)
        noFocus()
        val searchField = focusIn(R.id.list_pane)
        dispatchIme(500)
        removeField(searchField)
        assertSame(host.findViewById(R.id.list_pane), router.owner)

        runImeAnimation(imeAnimation()) { focusIn(R.id.detail_pane) }

        assertSame(host.findViewById(R.id.detail_pane), router.owner)
    }

    @Test
    fun `one-pane - nothing is routed`() {
        launch("w400dp-h800dp")
        assertNull(host.paneImeRouter)
        probe("detail", R.id.fragment_container_detail)
        focusIn(R.id.detail_pane)

        dispatchIme(500)

        assertEquals(500, received["detail"])
    }

    @Test
    fun `a recreation attaches a fresh router`() {
        launch("w840dp-h900dp")
        val first = requireNotNull(host.paneImeRouter)
        controller!!.recreate()
        settle()
        val second = requireNotNull(host.paneImeRouter)
        assertTrue(first !== second)
        probe("detail", R.id.fragment_container_detail)
        focusIn(R.id.list_pane)
        dispatchIme(500)
        assertEquals(0, received["detail"])
    }
}
