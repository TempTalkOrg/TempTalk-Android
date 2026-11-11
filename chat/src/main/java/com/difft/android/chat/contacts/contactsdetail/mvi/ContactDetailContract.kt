package com.difft.android.chat.contacts.contactsdetail.mvi

import com.difft.android.chat.contacts.contactsdetail.ContactDetailUiState
import org.difft.app.database.models.ContactorModel

object ContactDetailContract {
    data class Input(
        val contactId: String,
        val customId: String? = null,
        val contactName: String? = null,
        val avatar: String? = null,
        val joinedAt: String? = null,
        val sourceType: String? = null,
        val source: String? = null,
    )

    sealed interface Intent {
        
        data class Load(val input: Input, val preloadKey: String? = null) : Intent
        data object Refresh : Intent
        data object AddFriend : Intent
        data object RemoveNow : Intent
    }

    sealed interface Effect {
        data object ShowWait : Effect
        data object DismissWait : Effect
        data object FriendAdded : Effect
        data class AccountUnavailable(val removable: Boolean) : Effect
        data class Failed(val message: String?) : Effect
        data object Removed : Effect
        data object RemoveFailed : Effect
    }

    data class State(
        val input: Input? = null,
        val ui: ContactDetailUiState = ContactDetailUiState(),
        val generation: Int = 0,
        val submitting: Boolean = false,
        
        val fetched: ContactorModel? = null,
    )
}
