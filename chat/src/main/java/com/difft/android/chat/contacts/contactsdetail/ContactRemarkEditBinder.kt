package com.difft.android.chat.contacts.contactsdetail

import com.difft.android.base.utils.pane.PageWait
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.dismissWithView
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










internal class ContactRemarkEditBinder(
    private val fragment: Fragment,
    private val viewModelProvider: () -> ContactRemarkEditViewModel,
    private val onClose: () -> Unit,
) {
    
    private val pageWait = PageWait(fragment)

    private val avatarPickLauncher = AvatarPickLauncher.forFragment(fragment) { path ->
        viewModelProvider().dispatch(ContactRemarkEditContract.Intent.AvatarPicked(path))
    }

    




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
                        if (editing) backPressedDispatcher().addCallback(fragment.detailBackOwner(), backWhileEditing)
                        sheetHost()?.setExpandedForEditing(editing)
                    }
                }
                launch {
                    
                    
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

            ContactRemarkEditContract.Effect.ShowWait -> pageWait.show()
            ContactRemarkEditContract.Effect.DismissWait -> pageWait.dismiss()
            ContactRemarkEditContract.Effect.HideKeyboard -> hideKeyboard()
            is ContactRemarkEditContract.Effect.DeleteUploadTemp ->
                AvatarPickTempCleaner.deleteUploadedTemp(fragment.requireContext(), effect.path)
        }
    }

    
    private fun showUnsavedDialog() {
        val activity = fragment.requireActivity()
        fragment.dismissWithView(ComposeDialogManager.showMessageDialog(
            context = activity,
            
            title = "",
            message = activity.getString(R.string.contact_remark_unsaved_message),
            confirmText = activity.getString(R.string.unsaved_changes_save),
            cancelText = activity.getString(R.string.unsaved_changes_discard),
            cancelable = false,
            onConfirm = { viewModelProvider().dispatch(ContactRemarkEditContract.Intent.SaveAndClose) },
            onCancel = { viewModelProvider().dispatch(ContactRemarkEditContract.Intent.DiscardAndClose) },
        ))
    }

    
    private fun showAvatarActionSheet() {
        var dialog: ComposeDialog? = null
        dialog = fragment.dismissWithView(ComposeDialogManager.showBottomDialog(fragment.requireActivity()) {
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
        })
    }

    
    private fun hideKeyboard() {
        val window = (fragment.parentFragment as? DialogFragment)?.dialog?.window
            ?: fragment.activity?.window
            ?: return
        val root = fragment.view ?: return
        WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
    }
}
