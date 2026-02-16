package org.thoughtcrime.securesms.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.difft.android.base.activity.ActivityProvider
import com.difft.android.base.activity.ActivityType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.LinkDataEntity
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.ui.ChatActivity
import dagger.hilt.android.EntryPointAccessors
import util.AppForegroundObserver

/**
 * Transparent trampoline Activity that routes notification clicks to MainActivity
 * with appropriate flags based on app foreground state.
 *
 * - Foreground: Routes WITHOUT CLEAR_TASK, with EXTRA_OPEN_POPUP=true -> opens popup
 * - Background/Cold start: Routes WITH CLEAR_TASK -> normal deeplink flow
 *
 * Note: Uses Activity instead of BroadcastReceiver because Android 12+ blocks
 * BroadcastReceivers from starting Activities (trampoline restriction).
 */
class NotificationTrampolineActivity : Activity() {

    companion object {
        const val EXTRA_CONTACT_ID = "extra_contact_id"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_OPEN_POPUP = "extra_open_popup"

        fun createIntent(context: Context, contactId: String?, groupId: String?): Intent {
            return Intent(context, NotificationTrampolineActivity::class.java).apply {
                if (!groupId.isNullOrEmpty()) {
                    putExtra(EXTRA_GROUP_ID, groupId)
                } else {
                    putExtra(EXTRA_CONTACT_ID, contactId)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val contactId = intent.getStringExtra(EXTRA_CONTACT_ID)
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)

        if (contactId.isNullOrEmpty() && groupId.isNullOrEmpty()) {
            L.e { "[NotificationTrampoline] No contactId or groupId provided" }
            finish()
            return
        }

        val isForegrounded = AppForegroundObserver.isForegrounded()
        L.i { "[NotificationTrampoline] isForegrounded=$isForegrounded, contactId=$contactId, groupId=$groupId" }

        val mainIntent = createMainActivityIntent(contactId, groupId, isForegrounded)
        startActivity(mainIntent)
        finish()
    }

    private fun createMainActivityIntent(contactId: String?, groupId: String?, isForegrounded: Boolean): Intent {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            NotificationTrampolineEntryPoint::class.java
        )
        val activityProvider = entryPoint.activityProvider()

        return Intent(this, activityProvider.getActivityClass(ActivityType.MAIN)).apply {
            putExtra(LinkDataEntity.LINK_CATEGORY, LinkDataEntity.CATEGORY_MESSAGE)
            if (!groupId.isNullOrEmpty()) {
                putExtra(GroupChatContentActivity.INTENT_EXTRA_GROUP_ID, groupId)
            } else {
                putExtra(ChatActivity.BUNDLE_KEY_CONTACT_ID, contactId)
            }

            if (isForegrounded) {
                // Foreground: preserve existing pages, open popup
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(EXTRA_OPEN_POPUP, true)
            } else {
                // Background/Cold start: clear task for proper navigation
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    @dagger.hilt.EntryPoint
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    interface NotificationTrampolineEntryPoint {
        fun activityProvider(): ActivityProvider
    }
}
