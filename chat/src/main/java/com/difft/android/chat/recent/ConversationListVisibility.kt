package com.difft.android.chat.recent

import com.difft.android.messageserialization.db.store.ConversationUtils

/**
 * Whether the conversation list counts as visible for notification suppression: its fragment is
 * resumed AND the home screen's one-pane detail layer does not cover it. The list stays RESUMED
 * under the layer (ViewPager2 re-applies RESUMED to its current page), so the lifecycle alone
 * cannot tell.
 *
 * For the same reason, leaving a one-pane conversation does not resume the list as closing
 * ChatActivity did: [onReturnedToList] runs when the layer uncovers a resumed list, so the
 * owner's "back on the list" refresh runs there as well as from onResume.
 */
internal class ConversationListVisibility(
    private val onReturnedToList: () -> Unit = {},
    private val publish: (Boolean) -> Unit = { ConversationUtils.isConversationListVisible = it },
) {
    private var resumed = false
    private var covered = false

    fun onResumedChanged(resumed: Boolean) {
        this.resumed = resumed
        publish(this.resumed && !covered)
    }

    fun onCoveredChanged(covered: Boolean) {
        val uncovered = this.covered && !covered
        this.covered = covered
        publish(resumed && !this.covered)
        if (uncovered && resumed) onReturnedToList()
    }
}
