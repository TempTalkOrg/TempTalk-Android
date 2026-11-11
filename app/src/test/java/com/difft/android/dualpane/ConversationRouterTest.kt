package com.difft.android.dualpane

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import androidx.core.content.IntentCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.difft.android.IndexActivity
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config


class RouterHomeActivity : FragmentActivity(), ConversationNavigationCallback {
    val opened = mutableListOf<ConversationTarget>()
    override fun openConversation(target: ConversationTarget) {
        opened += target
    }

    override val isDualPaneMode: Boolean = false
    override val currentSelectedConversationId: String? = null
    override val detailCoversList: StateFlow<Boolean> = MutableStateFlow(false)
    override val homeTabBarObstructionPx: StateFlow<Int> = MutableStateFlow(0)
    override fun currentConversationOnScreen(): String? = null
    override fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long?) = Unit
    override fun onContactDetailSelected(contactId: String) = Unit
    override fun showDetailFragment(fragment: Fragment, tag: String?) = Unit
}


@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class ConversationRouterTest {

    private val chat = ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "u1", jumpTimestamp = 7L, sourceType = "t", source = "s")
    private val group = ConversationTarget(ConversationTarget.Kind.GROUP, "g1")

    private fun plainActivity(): FragmentActivity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()

    @Test
    fun `rule 1 - the home screen opens the conversation itself, also through a wrapped context`() {
        val home = Robolectric.buildActivity(RouterHomeActivity::class.java).setup().get()

        ConversationRouter.open(home, chat)
        ConversationRouter.open(ContextWrapper(home), group)

        assertEquals(listOf(chat, group), home.opened)
        assertNull("nothing is launched", shadowOf(home).nextStartedActivity)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `rule 2 - another Activity in a two-pane window brings the home screen forward`() {
        val search = plainActivity()

        ConversationRouter.open(search, chat)

        val started = shadowOf(search).nextStartedActivity
        assertEquals(IndexActivity::class.java.name, started.component?.className)
        assertEquals(chat, IntentCompat.getParcelableExtra(started, EXTRA_OPEN_CONVERSATION, ConversationTarget::class.java))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `rule 3 - one-pane keeps the full-window shell so Back returns to the launcher`() {
        val search = plainActivity()

        ConversationRouter.open(search, chat)
        val chatIntent = shadowOf(search).nextStartedActivity
        assertEquals(ChatActivity::class.java.name, chatIntent.component?.className)
        assertEquals("u1", chatIntent.contactorID)

        ConversationRouter.open(search, group)
        assertEquals(GroupChatContentActivity::class.java.name, shadowOf(search).nextStartedActivity.component?.className)
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a popup in a two-pane window opens in the detail pane`() {
        
        assertEquals(ConversationRouter.Route.BRING_HOME, ConversationRouter.route(plainActivity()))
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun `a popup on a one-pane phone opens a page of its own`() {
        assertEquals(ConversationRouter.Route.SHELL, ConversationRouter.route(plainActivity()))
    }

    @Test
    @Config(qualifiers = "w840dp-h900dp")
    fun `a context that is not an Activity gets the shell`() {
        assertEquals(ConversationRouter.Route.SHELL, ConversationRouter.route(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun `the open request is consumed once`() {
        val intent = OpenConversationExtra.put(Intent(), chat)

        assertEquals(chat, OpenConversationExtra.consume(intent, isRecreation = false))
        assertNull(OpenConversationExtra.consume(intent, isRecreation = false))
    }

    @Test
    fun `a recreation drops the request, its conversation was already restored`() {
        val intent = OpenConversationExtra.put(Intent(), chat)

        assertNull(OpenConversationExtra.consume(intent, isRecreation = true))
        assertNull(OpenConversationExtra.consume(intent, isRecreation = false))
    }
}
