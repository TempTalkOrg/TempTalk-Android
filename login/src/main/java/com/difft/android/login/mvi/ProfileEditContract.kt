package com.difft.android.login.mvi

object ProfileEditContract {
    data class State(
        val initialLoaded: Boolean = false,
        val contact: org.difft.app.database.models.ContactorModel? = null,
        val nameDraft: String = "",
        val avatarPath: String? = null,
        val nameDirty: Boolean = false,
        val avatarDirty: Boolean = false,
        val pickerPending: Boolean = false,
        val busy: Boolean = false,
        val completed: Boolean = false,
        val acceptedLocalPending: Boolean = false,
    )
    sealed interface Intent {
        data object Load : Intent
        data class ChangeName(val name: String) : Intent
        data class SelectAvatar(val path: String?) : Intent
        data class SetPickerPending(val pending: Boolean) : Intent
        data object Save : Intent
    }
    sealed interface Effect {
        data object Saved : Effect
        data object ReselectAvatar : Effect
        data object AcceptedLocalCommitFailed : Effect
        data class Failed(val message: String?) : Effect
    }
}
