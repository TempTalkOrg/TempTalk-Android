package com.difft.android.chat.recent

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.pane.ConversationTarget
import kotlinx.coroutines.flow.StateFlow





interface DualPaneSelectionListener {
    fun updateDualPaneSelection(selectedId: String?)
}







interface ConversationNavigationCallback : DualPaneHost {
    



    val currentSelectedConversationId: String?

    




    fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long? = null)

    




    fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long? = null)

    



    fun onContactDetailSelected(contactId: String)

    



    fun openConversation(target: ConversationTarget)

    



    fun currentConversationOnScreen(): String?

    
    val detailCoversList: StateFlow<Boolean>

    



    val conversationCoversList: StateFlow<Boolean> get() = detailCoversList
}
