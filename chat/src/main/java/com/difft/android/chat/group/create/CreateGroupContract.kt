package com.difft.android.chat.group.create

import androidx.annotation.StringRes
import com.difft.android.chat.group.GroupMemberModel


object CreateGroupContract {

    data class State(
        
        val rows: List<GroupMemberModel> = emptyList(),
        
        val selectedIds: List<String> = emptyList(),
        val query: String = "",
        val name: String = "",
        
        val avatarPath: String? = null,
        val avatarPicked: Boolean = false,
        
        val avatarAdopting: Boolean = false,
        val busy: Boolean = false,
        
        val completed: String? = null,
    )

    sealed interface Intent {
        data object Load : Intent
        data class Search(val query: String) : Intent
        data class Toggle(val uid: String) : Intent
        data class NameChanged(val name: String) : Intent
        data object PickAvatar : Intent
        data class AvatarPicked(val path: String) : Intent
        data object Submit : Intent
    }

    sealed interface Effect {
        data object RequestPickAvatar : Effect
        data class Toast(@StringRes val messageRes: Int? = null, val text: String? = null, val arg: String? = null) : Effect
    }
}
