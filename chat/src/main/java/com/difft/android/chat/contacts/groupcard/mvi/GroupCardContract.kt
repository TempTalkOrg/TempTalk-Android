package com.difft.android.chat.contacts.groupcard.mvi

import org.difft.app.database.models.GroupModel

object GroupCardContract {
    enum class Action { MESSAGE, CALL, INVITE, MORE, MEMBERS }

    sealed interface Intent {
        data object Refresh : Intent
        data class Open(val action: Action) : Intent
    }

    data class State(
        val groupId: String = "",
        val group: GroupModel? = null,
        val name: String = "",
        val memberCount: Int = 0,
        val messageExpirySeconds: Long = 0,
        val dissolveDays: Int? = null,
        val actionsEnabled: Boolean = false,
        val inviteEnabled: Boolean = false,
        val callAllowed: Boolean = false,
        val loading: Boolean = true,
    )

    sealed interface Effect {
        data class Navigate(val groupId: String, val action: Action) : Effect
        data class Call(val groupId: String, val name: String) : Effect
        data object CannotSpeak : Effect
        data object CannotInvite : Effect
    }
}
