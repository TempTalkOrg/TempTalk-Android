package com.difft.android.chat.contacts.contactsdetail.mvi

import org.difft.app.database.models.ContactorModel

/**
 * MVI contract for in-place remark (alias) editing on the contact card.
 *
 * Remark name and remark avatar are private to the current user and are two independent settings:
 * the avatar is committed as soon as a picture is picked (or restored); the name is committed only
 * by [Intent.SubmitName] ("Done"). Submits compare against the remark stored when edit mode was
 * entered ([State.editBaseline]); that baseline is frozen for the whole edit even if the contact is
 * refreshed. Closing with an unsaved name change asks the user to save or discard.
 *
 * The input starts at the stored remark (empty when there is none) — the contact's real name is
 * never prefilled; it is offered by the quick-fill shortcut instead ([Intent.QuickFillName]).
 */
object ContactRemarkEditContract {

    sealed interface Intent {
        /** Fed by the host whenever the rendered contact changes. */
        data class Load(val contactor: ContactorModel) : Intent
        data object EnterEdit : Intent
        data class ChangeName(val value: String) : Intent
        data object SubmitName : Intent
        /** Leave edit mode and drop the draft, staying on the card. */
        data object DiscardEdit : Intent
        /** Edit mode only: has a remark avatar → action sheet (photos / restore); else → picker. */
        data object AvatarClick : Intent
        data class AvatarPicked(val path: String) : Intent
        data object RestoreAvatar : Intent
        /** Quick-fill shortcut: append the contact's real name to the draft. */
        data object QuickFillName : Intent
        /** Close / back: asks to save when there is an unsaved remark change, otherwise closes. */
        data object RequestClose : Intent
        /** Unsaved-changes dialog → "Discard". */
        data object DiscardAndClose : Intent
        /** Unsaved-changes dialog → "Save": submits, closes on success, stays on failure. */
        data object SaveAndClose : Intent
    }

    data class State(
        val contactId: String = "",
        /** Current remark name from the remark chain (cache → contactor → group member), "" when none. */
        val remarkName: String = "",
        /** Non-remark name (public name → name → group display name), "" when none; prefills the input. */
        val originalName: String = "",
        val hasRemarkAvatar: Boolean = false,
        val isEditing: Boolean = false,
        /** Input text. Seeded with `remarkName` (so empty when there is no remark) on [Intent.EnterEdit]. */
        val editingName: String = "",
        /** Stored remark when edit mode was entered; the only comparison base for submit / unsaved checks. */
        val editBaseline: String = "",
        /** `isEditing && trimmed input != trimmed baseline`. Drives Done, the unsaved dialog and gesture locks. */
        val hasUnsavedChanges: Boolean = false,
        /** Real name offered by the quick-fill row; "" when the contact has none. Frozen while editing. */
        val quickFillName: String = "",
        /** Decided when edit mode opens and cleared by using the shortcut; typing never changes it. */
        val showQuickFill: Boolean = false,
        val isSubmitting: Boolean = false,
    )

    sealed interface Effect {
        data object RequestPickAvatar : Effect
        data object ShowAvatarActionSheet : Effect
        /** Server-provided reason; null falls back to the generic network error. */
        data class ShowToast(val text: String?) : Effect
        data object ShowWait : Effect
        data object DismissWait : Effect
        data object HideKeyboard : Effect
        /** Upload temp no longer needed once the server copy renders. */
        data class DeleteUploadTemp(val path: String) : Effect
        /** "Save remark changes?" with Save / Discard. */
        data object ShowUnsavedDialog : Effect
        /** Host closes the card (dismiss the sheet / finish the Activity). */
        data object Close : Effect
    }
}
