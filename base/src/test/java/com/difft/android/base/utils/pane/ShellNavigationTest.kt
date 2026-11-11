package com.difft.android.base.utils.pane

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ShellNavigationTest {

    class ViewFragment : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context)
    }

    class SheetFragment : DialogFragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
            FrameLayout(inflater.context).apply { id = SHEET_CONTENT_ID }
    }

    private fun activity(): FragmentActivity =
        Robolectric.buildActivity(FragmentActivity::class.java).also {
            it.get().setTheme(androidx.appcompat.R.style.Theme_AppCompat_Light)
        }.setup().get()

    @Test
    fun `a page in a shell Activity finishes it and uses its view lifecycle for Back`() {
        val activity = activity()
        val page = ViewFragment()
        activity.supportFragmentManager.beginTransaction().add(android.R.id.content, page).commitNow()

        val navigation = page.detailNavigation()
        assertTrue(navigation is ShellNavigation)
        assertSame(page.viewLifecycleOwner, page.detailBackOwner())
        assertTrue(navigation.canPop(page))

        assertTrue(navigation.pop(page))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `a page inside a sheet dismisses the sheet, not the Activity`() {
        val activity = activity()
        val sheet = SheetFragment()
        sheet.showNow(activity.supportFragmentManager, "sheet")
        val page = ViewFragment()
        sheet.childFragmentManager.beginTransaction().add(SHEET_CONTENT_ID, page).commitNow()

        assertTrue(page.detailNavigation().pop(page))
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(activity.isFinishing)
        assertFalse(sheet.isAdded)
    }

    @Test
    fun `only a positive jump timestamp counts as a jump`() {
        val kind = ConversationTarget.Kind.ONE_ON_ONE
        assertFalse(ConversationTarget(kind, "a").hasJump)
        assertFalse(ConversationTarget(kind, "a", jumpTimestamp = 0L).hasJump)
        assertTrue(ConversationTarget(kind, "a", jumpTimestamp = 1L).hasJump)
    }

    private companion object {
        const val SHEET_CONTENT_ID = 0x7f0a_beef
    }
}
