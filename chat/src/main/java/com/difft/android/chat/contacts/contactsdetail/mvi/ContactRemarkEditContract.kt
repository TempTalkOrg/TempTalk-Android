package com.difft.android.chat.contacts.contactsdetail.mvi

import org.difft.app.database.models.ContactorModel













object ContactRemarkEditContract {

    sealed interface Intent {
        
        data class Load(val contactor: ContactorModel) : Intent
        data object EnterEdit : Intent
        data class ChangeName(val value: String) : Intent
        data object SubmitName : Intent
        
        data object DiscardEdit : Intent
        
        data object AvatarClick : Intent
        data class AvatarPicked(val path: String) : Intent
        data object RestoreAvatar : Intent
        
        data object QuickFillName : Intent
        
        data object RequestClose : Intent
        
        data object DiscardAndClose : Intent
        
        data object SaveAndClose : Intent
    }

    data class State(
        val contactId: String = "",
        
        val remarkName: String = "",
        
        val originalName: String = "",
        val hasRemarkAvatar: Boolean = false,
        val isEditing: Boolean = false,
        
        val editingName: String = "",
        
        val editBaseline: String = "",
        
        val hasUnsavedChanges: Boolean = false,
        
        val quickFillName: String = "",
        
        val showQuickFill: Boolean = false,
        val isSubmitting: Boolean = false,
    )

    sealed interface Effect {
        data object RequestPickAvatar : Effect
        data object ShowAvatarActionSheet : Effect
        
        data class ShowToast(val text: String?) : Effect
        data object ShowWait : Effect
        data object DismissWait : Effect
        data object HideKeyboard : Effect
        
        data class DeleteUploadTemp(val path: String) : Effect
        
        data object ShowUnsavedDialog : Effect
        
        data object Close : Effect
    }
}
