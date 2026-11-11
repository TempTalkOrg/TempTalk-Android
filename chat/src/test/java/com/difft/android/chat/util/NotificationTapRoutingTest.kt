package com.difft.android.chat.util

import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.difft.android.chat.group.GroupChatPopupActivity
import com.difft.android.chat.ui.ChatPopupActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationTapRoutingTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `foreground MainActivity start joins the current task`() {
        
        assertEquals(0, NotificationTrampolineActivity.mainActivityFlags(isForegrounded = true))
    }

    @Test
    fun `background MainActivity start still clears the task`() {
        assertEquals(
            Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK,
            NotificationTrampolineActivity.mainActivityFlags(isForegrounded = false),
        )
    }

    @Test
    fun `the popup opens after the notification launch, not inside it`() {
        
        val popup = ChatPopupIntents.create(context, contactId = "+1234567", groupId = null)!!
        val app = shadowOf(context as android.app.Application)

        NotificationTrampolineActivity.startPopupAfterLaunch(context, popup) { null }
        assertNull(app.nextStartedActivity)

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NotificationTrampolineActivity.POPUP_DELAY_MS))
        assertEquals(ChatPopupActivity::class.java.name, app.nextStartedActivity?.component?.className)
    }

    @Test
    fun `a popup blocked by the time the delay ends is dropped`() {
        
        val popup = ChatPopupIntents.create(context, contactId = "+1234567", groupId = null)!!
        val app = shadowOf(context as android.app.Application)
        var checks = 0

        NotificationTrampolineActivity.startPopupAfterLaunch(context, popup) { checks++; "app_lock" }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(NotificationTrampolineActivity.POPUP_DELAY_MS))

        assertEquals(1, checks)
        assertNull(app.nextStartedActivity)
    }

    @Test
    fun `group popup when the group id is valid`() {
        val intent = ChatPopupIntents.create(context, contactId = "+1234567", groupId = GID)!!
        assertEquals(GroupChatPopupActivity::class.java.name, intent.component?.className)
        assertEquals(GID, intent.getStringExtra(GroupChatPopupActivity.INTENT_EXTRA_GROUP_ID))
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun `contact popup when only the contact id is valid`() {
        val intent = ChatPopupIntents.create(context, contactId = "+1234567", groupId = "bad")!!
        assertEquals(ChatPopupActivity::class.java.name, intent.component?.className)
        assertEquals("+1234567", intent.getStringExtra(ChatPopupActivity.BUNDLE_KEY_CONTACT_ID))
    }

    @Test
    fun `no popup for malformed ids`() {
        assertNull(ChatPopupIntents.create(context, contactId = "1234567", groupId = null))
        assertNull(ChatPopupIntents.create(context, contactId = null, groupId = "short"))
    }

    private companion object {
        const val GID = "0123456789abcdef0123456789ABCDEF"
    }
}
