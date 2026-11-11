package com.difft.android.chat.recent

import com.difft.android.messageserialization.db.store.ConversationUtils











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
