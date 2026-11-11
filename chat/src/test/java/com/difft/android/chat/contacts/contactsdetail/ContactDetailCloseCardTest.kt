package com.difft.android.chat.contacts.contactsdetail

import android.os.Bundle
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.DetailNavigation
import com.difft.android.base.utils.pane.DetailStackHost
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


class RecordingStackHost : Fragment(), DetailStackHost {
    var pops = 0
    var closes = 0
    override val detailNavigation: DetailNavigation = object : DetailNavigation {
        override fun push(from: Fragment, destination: DetailDestination): Boolean = false
        override fun pop(from: Fragment): Boolean {
            pops++
            return true
        }
        override fun openConversation(target: ConversationTarget) = Unit
        override fun closeConversation(from: Fragment) {
            closes++
        }
        override fun canPop(from: Fragment): Boolean = true
    }

    override fun detailBackOwnerFor(fragment: Fragment): LifecycleOwner = fragment
}

class CardSheet : BottomSheetDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        FrameLayout(inflater.context)
}






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ContactDetailCloseCardTest {

    private fun activity(): AppCompatActivity =
        Robolectric.buildActivity(AppCompatActivity::class.java).also {
            it.get().setTheme(com.google.android.material.R.style.Theme_MaterialComponents_Light)
        }.setup().get()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `in the home detail stack the card pops, and a removed contact closes the stack`() {
        val activity = activity()
        val host = RecordingStackHost()
        activity.supportFragmentManager.beginTransaction().add(host, "host").commitNow()
        val card = Fragment()
        host.childFragmentManager.beginTransaction().add(card, "card").commitNow()

        ContactDetailFragment.closeCard(card)
        assertEquals(1, host.pops)

        ContactDetailFragment.closeCard(card, contactGone = true)
        assertEquals(1, host.closes)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `in a bottom sheet the card dismisses the sheet and the Activity stays`() {
        val activity = activity()
        val sheet = CardSheet()
        sheet.showNow(activity.supportFragmentManager, "sheet")
        val card = Fragment()
        sheet.childFragmentManager.beginTransaction().add(card, "card").commitNow()

        ContactDetailFragment.closeCard(card, contactGone = true)
        idle()

        assertFalse(sheet.isAdded)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun `in the ContactDetailActivity shell the card finishes the Activity`() {
        val activity = activity()
        val card = Fragment()
        activity.supportFragmentManager.beginTransaction().add(card, "card").commitNow()

        ContactDetailFragment.closeCard(card)

        assertTrue(activity.isFinishing)
    }
}
