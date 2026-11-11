package com.difft.android.chat.group.create

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.recent.ListPaneLayerOpener
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull






@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CreateGroupEntryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the destination builds the page and the shell intent with the same preselection`() {
        val destination = CreateGroupDestination(listOf("a", "a", "b"))

        val page = destination.newFragment()
        val intent = destination.shellIntent(context)

        assertEquals(listOf("a", "b"), CreateGroupFragment.preselectedIds(page.arguments))
        assertEquals(CreateGroupActivity::class.java.name, intent.component?.className)
        assertEquals(listOf("a", "b"), intent.getStringArrayListExtra(CreateGroupViewModel.ARG_PRESELECTED)?.toList())
        assertNull(destination.subjectId)
        assertEquals(CreateGroupDestination(listOf("c")).pageKey, destination.pageKey)
    }

    @Test
    fun `a conversation entry outside a detail stack starts the shell, a list entry uses the layer host first`() {
        val activity = Robolectric.buildActivity(PlainHost::class.java).setup().get()
        val page = Fragment()
        activity.supportFragmentManager.beginTransaction().add(page, "page").commitNow()

        openCreateGroupFromConversation(page, "a")
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(CreateGroupActivity::class.java.name, started.component?.className)
        assertEquals(listOf("a"), started.getStringArrayListExtra(CreateGroupViewModel.ARG_PRESELECTED)?.toList())

        activity.layerOpens = 0
        openCreateGroupFromList(page)
        assertEquals(1, activity.layerOpens)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `no entry opens a second page while one shows`() {
        val activity = Robolectric.buildActivity(PlainHost::class.java).setup().get()
        val page = Fragment()
        activity.supportFragmentManager.beginTransaction().add(page, "page").commitNow()
        activity.showing = true

        openCreateGroupFromConversation(page, "a")
        openCreateGroupFromList(page)

        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(0, activity.layerOpens)
    }

    
    class PlainHost : FragmentActivity(), ListPaneLayerOpener {
        var showing = false
        var layerOpens = 0
        override fun openSearchLayer(): Boolean = false
        override fun openFoldedGroupsLayer(): Boolean = false
        override fun openCreateGroupLayer(preselectedIds: List<String>): Boolean {
            layerOpens++
            return true
        }
        override fun showsCreateGroup(): Boolean = showing
    }
}
