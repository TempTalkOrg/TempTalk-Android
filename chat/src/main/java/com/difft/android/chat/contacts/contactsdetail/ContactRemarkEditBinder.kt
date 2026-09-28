package com.difft.android.chat.contacts.contactsdetail

import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickLauncher
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditContract
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactRemarkEditViewModel
import com.difft.android.chat.contacts.contactsremark.RemarkAvatarActionSheet
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Binds [ContactRemarkEditViewModel] to the contact card host: maps effects to UI work (picker,
 * action sheet, wait dialog, toasts, keyboard), routes back-press to "leave edit mode" while
 * editing, and pushes the bottom-sheet host to full height while editing.
 *
 * Construct as a Fragment field: the picker registers its permission contract at construction,
 * which must happen before the Fragment is STARTED. [viewModelProvider] is deferred because
 * `viewModels()` cannot be resolved before the Fragment is attached.
 */
internal class ContactRemarkEditBinder(
    private val fragment: Fragment,
    private val viewModelProvider: () -> ContactRemarkEditViewModel,
    private val onClose: () -> Unit,
) {
    private val avatarPickLauncher = AvatarPickLauncher.forFragment(fragment) { path ->
        viewModelProvider().dispatch(ContactRemarkEditContract.Intent.AvatarPicked(path))
    }

    /**
     * Back while editing behaves like the close button: the ViewModel asks to save when there is an
     * unsaved change, otherwise closes. Registered only for the duration of an edit: the dispatcher
     * is LIFO, and a host callback added later (dual-pane IndexActivity adds "expand list pane" on
     * first collapse) would otherwise win.
     */
    private val backWhileEditing = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            viewModelProvider().dispatch(ContactRemarkEditContract.Intent.RequestClose)
        }
    }

    fun observe() {
        val owner = fragment.viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val viewModel = viewModelProvider()
                launch {
                    viewModel.state.map { it.isEditing }.distinctUntilChanged().collect { editing ->
                        backWhileEditing.remove()
                        if (editing) backPressedDispatcher().addCallback(owner, backWhileEditing)
                        sheetHost()?.setExpandedForEditing(editing)
                    }
                }
                launch {
                    // Swipe-down and scrim taps cannot be intercepted with a prompt, so they are
                    // disabled while an unsaved change exists (product spec allows disabling them).
                    viewModel.state.map { it.hasUnsavedChanges }.distinctUntilChanged().collect { dirty ->
                        sheetHost()?.setDismissGesturesEnabled(!dirty)
                    }
                }
                launch {
                    viewModel.effect.collect { effect -> handleEffect(effect) }
                }
            }
        }
    }

    /** In a bottom sheet, back is delivered to the dialog's dispatcher, not the Activity's. */
    private fun backPressedDispatcher() =
        ((fragment.parentFragment as? DialogFragment)?.dialog as? ComponentDialog)?.onBackPressedDispatcher
            ?: fragment.requireActivity().onBackPressedDispatcher

    private fun sheetHost() = fragment.parentFragment as? ContactDetailBottomSheetDialogFragment

    private fun handleEffect(effect: ContactRemarkEditContract.Effect) {
        when (effect) {
            ContactRemarkEditContract.Effect.ShowUnsavedDialog -> showUnsavedDialog()
            ContactRemarkEditContract.Effect.Close -> onClose()
            ContactRemarkEditContract.Effect.RequestPickAvatar -> avatarPickLauncher.launch()
            ContactRemarkEditContract.Effect.ShowAvatarActionSheet -> showAvatarActionSheet()
            is ContactRemarkEditContract.Effect.ShowToast -> {
                if (effect.text.isNullOrBlank()) ToastUtil.show(R.string.chat_net_error) else ToastUtil.showLong(effect.text)
            }

            ContactRemarkEditContract.Effect.ShowWait -> ComposeDialogManager.showWait(fragment.requireActivity(), "")
            ContactRemarkEditContract.Effect.DismissWait -> ComposeDialogManager.dismissWait()
            ContactRemarkEditContract.Effect.HideKeyboard -> hideKeyboard()
            is ContactRemarkEditContract.Effect.DeleteUploadTemp ->
                AvatarPickTempCleaner.deleteUploadedTemp(fragment.requireContext(), effect.path)
        }
    }

    /** Must pick Save or Discard; tapping outside is not an answer, so the dialog is not cancelable. */
    private fun showUnsavedDialog() {
        val activity = fragment.requireActivity()
        ComposeDialogManager.showMessageDialog(
            context = activity,
            // The prompt is the body text; an empty title avoids a blank block between title and buttons.
            title = "",
            message = activity.getString(R.string.contact_remark_unsaved_message),
            confirmText = activity.getString(R.string.unsaved_changes_save),
            cancelText = activity.getString(R.string.unsaved_changes_discard),
            cancelable = false,
            onConfirm = { viewModelProvider().dispatch(ContactRemarkEditContract.Intent.SaveAndClose) },
            onCancel = { viewModelProvider().dispatch(ContactRemarkEditContract.Intent.DiscardAndClose) },
        )
    }

    /** Existing remark avatar: offer photos / revert; otherwise the ViewModel goes straight to the picker. */
    private fun showAvatarActionSheet() {
        var dialog: ComposeDialog? = null
        dialog = ComposeDialogManager.showBottomDialog(fragment.requireActivity()) {
            RemarkAvatarActionSheet(
                onChoosePhotos = {
                    dialog?.dismiss()
                    avatarPickLauncher.launch()
                },
                onRestore = {
                    dialog?.dismiss()
                    viewModelProvider().dispatch(ContactRemarkEditContract.Intent.RestoreAvatar)
                },
                onCancel = { dialog?.dismiss() },
            )
        }
    }

    /** The card may live in a dialog window (bottom sheet) or the Activity window. */
    private fun hideKeyboard() {
        val window = (fragment.parentFragment as? DialogFragment)?.dialog?.window
            ?: fragment.activity?.window
            ?: return
        val root = fragment.view ?: return
        WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
    }
}
