package com.difft.android.chat.group.mvi

import androidx.annotation.StringRes
import com.difft.android.network.group.GroupAvatarData
import org.difft.app.database.models.GroupModel








object GroupInfoHeaderContract {

    
    enum class ExitSource {
        
        Back,
        
        OutsideTap,
    }

    sealed interface Intent {
        
        data class Load(val group: GroupModel) : Intent
        data object EnterEdit : Intent
        data class ChangeName(val value: String) : Intent
        data object SubmitName : Intent
        data object DiscardEdit : Intent
        
        data object AvatarClick : Intent
        data class AvatarPicked(val path: String) : Intent
        
        data class RequestExit(val source: ExitSource) : Intent
        
        data class DiscardExit(val source: ExitSource) : Intent
        
        data class SaveExit(val source: ExitSource) : Intent
    }

    data class State(
        val groupId: String = "",
        val name: String = "",
        val avatarData: GroupAvatarData? = null,
        
        val pendingLocalAvatarPath: String? = null,
        val isEncrypted: Boolean = false,
        val isEditing: Boolean = false,
        val editingName: String = "",
        
        val editBaseline: String = "",
        
        val hasEditedInput: Boolean = false,
        
        val hasUnsavedChanges: Boolean = false,
        
        val nameEditable: Boolean = false,
        
        val avatarEditable: Boolean = false,
        val isSubmitting: Boolean = false,
    )

    sealed interface Effect {
        data object RequestPickAvatar : Effect
        data class PreviewAvatar(val data: GroupAvatarData) : Effect
        
        data class ShowToast(val text: String?) : Effect
        data class ShowToastRes(@StringRes val resId: Int) : Effect
        data object ShowWait : Effect
        data object DismissWait : Effect
        data object HideKeyboard : Effect
        
        data class ShowUnsavedDialog(val source: ExitSource) : Effect
        
        data object Finish : Effect
    }
}
