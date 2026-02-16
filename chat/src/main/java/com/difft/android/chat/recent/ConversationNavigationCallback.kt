package com.difft.android.chat.recent

import androidx.fragment.app.Fragment
import com.difft.android.base.utils.DualPaneHost

/**
 * Callback interface for handling conversation navigation
 * Used to support both single-pane (Activity) and dual-pane (Fragment) navigation
 * 
 * Extends DualPaneHost to provide unified dual-pane support across all features
 */
interface ConversationNavigationCallback : DualPaneHost {
    /**
     * Called when a one-on-one conversation is selected
     * @param contactId The contact/user ID
     * @param jumpMessageTimestamp Optional timestamp to jump to specific message
     */
    fun onOneOnOneConversationSelected(contactId: String, jumpMessageTimestamp: Long? = null)

    /**
     * Called when a group conversation is selected
     * @param groupId The group ID
     * @param jumpMessageTimestamp Optional timestamp to jump to specific message
     */
    fun onGroupConversationSelected(groupId: String, jumpMessageTimestamp: Long? = null)

    /**
     * Called when a contact detail is selected (for dual-pane mode)
     * @param contactId The contact/user ID
     */
    fun onContactDetailSelected(contactId: String)
}