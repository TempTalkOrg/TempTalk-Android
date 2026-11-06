package com.difft.android.chat.group.mvi

import androidx.annotation.StringRes
import com.difft.android.chat.group.GroupMemberModel

object GroupPageContract {
    enum class Page { MANAGEMENT, MODERATORS, MEMBERS }
    enum class ModeratorAction { ADD, REMOVE, TRANSFER }
    enum class MemberAction { ADD, REMOVE }
    enum class Setting { INVITATION, PUBLISH, CRITICAL_ALERT }

    data class State(
        val rows: List<GroupMemberModel> = emptyList(),
        val allRows: List<GroupMemberModel> = emptyList(),
        val selectedIds: Set<String> = emptySet(),
        val eligibleIds: Set<String> = emptySet(),
        val query: String = "",
        val loaded: Boolean = false,
        val busy: Boolean = false,
        val allowed: Boolean = false,
        val invitation: Boolean = false,
        val publish: Boolean = false,
        val criticalAlert: Boolean = false,
        val acceptedSettings: Map<Setting, Boolean> = emptyMap(),
        val pending: GroupMutationRepository.Pending? = null,
        val generation: Long = 0,
        val closeSubject: Boolean = false,
        val completionPopCount: Int? = null,
    )

    sealed interface Intent {
        data object Load : Intent
        data class Search(val query: String) : Intent
        data class Select(val uid: String) : Intent
        data class ChangeSetting(val setting: Setting, val checked: Boolean) : Intent
        data object Submit : Intent
        data object Retry : Intent
    }

    sealed interface Effect {
        /** [messageRes] wins over [text]; [messageArg] formats it. Completion is kept in [State.completionPopCount]. */
        data class Message(
            val text: String? = null,
            val accepted: Boolean = false,
            @StringRes val messageRes: Int? = null,
            val messageArg: String? = null,
        ) : Effect
        data object CloseSubject : Effect
    }
}

/** Selection always belongs to the full eligible set, independent of a search filter. */
internal fun selectGroupMember(
    selected: Set<String>, eligible: Set<String>, uid: String, single: Boolean, enabled: Boolean,
): Set<String> = when {
    !enabled || uid !in eligible -> selected
    uid in selected -> selected - uid
    single -> setOf(uid)
    else -> selected + uid
}
