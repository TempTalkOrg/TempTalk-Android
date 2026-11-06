package com.difft.android.chat.ui

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import com.difft.android.base.utils.pane.ConversationOpener
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupChatContentActivity.Companion.groupID
import com.difft.android.chat.ui.ChatActivity.Companion.contactorID
import com.difft.android.chat.ui.ChatActivity.Companion.jumpMessageTimeStamp
import com.difft.android.chat.ui.ChatActivity.Companion.source
import com.difft.android.chat.ui.ChatActivity.Companion.sourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** An Application that routes conversations, as TempTalkApplication does. */
class RoutingTestApplication : Application(), ConversationOpener {
    val opened = mutableListOf<ConversationTarget>()
    override fun openConversation(context: Context, target: ConversationTarget) {
        opened += target
    }
}

/**
 * `ChatActivity.startActivity` / `GroupChatContentActivity.startActivity` keep their call sites
 * and go through the app's router when one is installed; otherwise they open the shell as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationShellsTest {

    private fun activity() = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    @Test
    @Config(application = RoutingTestApplication::class)
    fun `the factories hand the conversation to the installed router`() {
        val activity = activity()

        ChatActivity.startActivity(activity, "u1", sourceType = "t", source = "s", jumpMessageTimeStamp = 9L)
        GroupChatContentActivity.startActivity(activity, "g1")

        val app = RuntimeEnvironment.getApplication() as RoutingTestApplication
        assertEquals(
            listOf(
                ConversationTarget(ConversationTarget.Kind.ONE_ON_ONE, "u1", 9L, "t", "s"),
                ConversationTarget(ConversationTarget.Kind.GROUP, "g1", null),
            ),
            app.opened,
        )
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    @Config(application = Application::class)
    fun `without a router the full-window shells open with today's extras`() {
        val activity = activity()

        ChatActivity.startActivity(activity, "u1", sourceType = "t", source = "s", jumpMessageTimeStamp = 9L)
        val chat = shadowOf(activity).nextStartedActivity
        assertEquals(ChatActivity::class.java.name, chat.component?.className)
        assertEquals("u1", chat.contactorID)
        assertEquals("t", chat.sourceType)
        assertEquals("s", chat.source)
        assertEquals(9L, chat.jumpMessageTimeStamp)

        GroupChatContentActivity.startActivity(activity, "g1")
        val group = shadowOf(activity).nextStartedActivity
        assertEquals(GroupChatContentActivity::class.java.name, group.component?.className)
        assertEquals("g1", group.groupID)
    }
}
