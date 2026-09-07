package com.difft.android.chat.group.mvi

import androidx.annotation.StringRes
import com.difft.android.network.group.GroupAvatarData
import org.difft.app.database.models.GroupModel

/**
 * MVI contract for the group-settings identity header: in-place group name / avatar editing.
 *
 * Name and avatar are two independent settings: the avatar uploads as soon as a picture is
 * picked; the name is committed only by [Intent.SubmitName] (top-bar "Done"). Leaving edit mode
 * any other way discards the pending name silently.
 */
object GroupInfoHeaderContract {

    /** Where an edit-mode exit came from; decides what happens after the draft is resolved. */
    enum class ExitSource {
        /** System back or the title-bar arrow: leaves the page. */
        Back,
        /** A tap on another setting row: leaves edit mode and stays on the page. */
        OutsideTap,
    }

    sealed interface Intent {
        /** Fed by the host every time it receives a fresh [GroupModel]. */
        data class Load(val group: GroupModel) : Intent
        data object EnterEdit : Intent
        data class ChangeName(val value: String) : Intent
        data object SubmitName : Intent
        data object DiscardEdit : Intent
        /** Browse → preview; Edit + avatarEditable → pick a new picture. */
        data object AvatarClick : Intent
        data class AvatarPicked(val path: String) : Intent
        /** Back / outside tap while editing: asks to save when there is an unsaved change, else exits. */
        data class RequestExit(val source: ExitSource) : Intent
        /** Unsaved-changes dialog → "Discard". */
        data class DiscardExit(val source: ExitSource) : Intent
        /** Unsaved-changes dialog → "Save": submits, then completes the exit on success. */
        data class SaveExit(val source: ExitSource) : Intent
    }

    data class State(
        val groupId: String = "",
        val name: String = "",
        val avatarData: GroupAvatarData? = null,
        /** Locally picked file shown until the server copy lands via the next [Intent.Load]. */
        val pendingLocalAvatarPath: String? = null,
        val isEncrypted: Boolean = false,
        val isEditing: Boolean = false,
        val editingName: String = "",
        /** Group name when edit mode was entered; frozen for the edit, the only submit / unsaved comparison base. */
        val editBaseline: String = "",
        /** True once the input text actually changed during this edit. */
        val hasEditedInput: Boolean = false,
        /** `isEditing && hasEditedInput && trimmed input != trimmed baseline`. */
        val hasUnsavedChanges: Boolean = false,
        /** Plain groups always; encrypted groups only with a local R_group key. */
        val nameEditable: Boolean = false,
        /** Encrypted groups with a local R_group key only (avatar is always sent encrypted). */
        val avatarEditable: Boolean = false,
        val isSubmitting: Boolean = false,
    )

    sealed interface Effect {
        data object RequestPickAvatar : Effect
        data class PreviewAvatar(val data: GroupAvatarData) : Effect
        /** Server-provided reason; null falls back to the generic network error. */
        data class ShowToast(val text: String?) : Effect
        data class ShowToastRes(@StringRes val resId: Int) : Effect
        data object ShowWait : Effect
        data object DismissWait : Effect
        data object HideKeyboard : Effect
        /** "Save group name changes?" with Save / Discard. */
        data class ShowUnsavedDialog(val source: ExitSource) : Effect
        /** Host leaves the page. */
        data object Finish : Effect
    }
}
