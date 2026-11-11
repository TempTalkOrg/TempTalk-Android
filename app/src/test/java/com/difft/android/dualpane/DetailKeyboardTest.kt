package com.difft.android.dualpane

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.base.utils.pane.SoftInputModeLease
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CHATS
import com.difft.android.dualpane.PaneNavigator.Companion.TAB_CONTACTS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit






@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], qualifiers = "w840dp-h900dp")
class DetailKeyboardTest {

    class KeyboardPage : Fragment() {
        var focusCalls = 0
        private lateinit var input: EditText

        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            EditText(inflater.context).also {
                it.id = View.generateViewId()
                input = it
            }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            detailKeyboard(showOnFirstOpen = true, focus = ::showKeyboard, savedInstanceState = savedInstanceState)
        }

        fun showKeyboard() {
            focusCalls++
            input.requestFocus()
            imm(requireContext()).showSoftInput(input, 0)
        }
    }

    



    class LeasingRoot : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            SoftInputModeLease.acquireResize(requireActivity(), this, conversationRoot = true)
        }

        override fun onDestroyView() {
            SoftInputModeLease.release(requireActivity(), this)
            super.onDestroyView()
        }
    }

    private var controller: ActivityController<PaneNavigatorTestHost>? = null
    private val host: PaneNavigatorTestHost get() = controller!!.get()
    private val nav: PaneNavigator get() = host.navigator

    @After
    fun tearDown() {
        runCatching { controller?.pause()?.stop()?.destroy() }
    }

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)

    private fun keyboardShown(): Boolean = shadowOf(imm(host)).isSoftInputVisible

    private fun pushKeyboardPage(): KeyboardPage {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("conversation"), selectionId = "u1")
        settle()
        val page = KeyboardPage()
        nav.push(TAB_CHATS, page)
        settle()
        return page
    }

    private fun keyboardPage(): KeyboardPage = host.host(TAB_CHATS)!!.topPage() as KeyboardPage

    @Test
    fun `a fresh open shows the keyboard once, a recreation does not`() {
        val page = pushKeyboardPage()
        assertEquals(1, page.focusCalls)
        assertTrue(keyboardShown())

        controller!!.pause().resume()
        settle()
        assertEquals("only the first resume", 1, page.focusCalls)

        controller!!.recreate()
        settle()
        assertEquals("not after a fold recreation", 0, keyboardPage().focusCalls)
    }

    @Test
    fun `covering the page hides its keyboard`() {
        pushKeyboardPage()
        assertTrue(keyboardShown())

        nav.push(TAB_CHATS, StackPageFragment.newInstance("next"))
        settle()

        assertFalse(keyboardShown())
    }

    @Test
    fun `popping the page hides its keyboard`() {
        pushKeyboardPage()
        assertTrue(keyboardShown())

        nav.pop(TAB_CHATS)
        settle()

        assertFalse(keyboardShown())
    }

    private fun mode(): Int = host.window.attributes.softInputMode

    private fun resizes(): Boolean =
        mode() and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST == WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

    @Test
    @Config(sdk = [29])
    fun `API 29 - the keyboard page holds the resize lease only while it is the visible top`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, StackPageFragment.newInstance("card"), selectionId = "u1")
        settle()
        assertFalse(resizes())

        nav.push(TAB_CHATS, KeyboardPage())
        settle()
        assertTrue("acquired at ON_START", resizes())

        nav.push(TAB_CHATS, StackPageFragment.newInstance("next"))
        settle()
        assertFalse("released when covered", resizes())
        nav.pop(TAB_CHATS)
        settle()
        assertTrue("acquired again on reveal", resizes())

        controller!!.pause().stop()
        assertFalse("released in the background", resizes())
        controller!!.start().resume()
        settle()
        assertTrue("acquired again on return", resizes())
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 - a conversation closed in another tab keeps the keyboard page's resize`() {
        controller = Robolectric.buildActivity(PaneNavigatorTestHost::class.java).setup()
        nav.replaceRoot(TAB_CHATS, LeasingRoot(), selectionId = "c")
        settle()
        assertEquals(
            "a conversation root writes what ChatFragment wrote",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
            mode(),
        )
        nav.push(TAB_CHATS, KeyboardPage())
        nav.replaceRoot(TAB_CONTACTS, LeasingRoot(), selectionId = "g")
        settle()

        nav.clear(TAB_CONTACTS) 
        settle()
        assertTrue(resizes())

        nav.pop(TAB_CHATS)
        nav.clear(TAB_CHATS)
        settle()
        assertEquals(
            "the last release restores what a conversation view leaving wrote",
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN,
            mode(),
        )
    }

    private companion object {
        fun imm(context: Context): InputMethodManager =
            context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }
}
