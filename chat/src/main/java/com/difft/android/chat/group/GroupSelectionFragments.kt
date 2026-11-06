package com.difft.android.chat.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.isVisibleTopNow
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailDestination
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.databinding.ChatActivityGroupSelectMemberBinding
import com.difft.android.chat.group.mvi.GroupPageContract.Effect
import com.difft.android.chat.group.mvi.GroupPageContract.Intent
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import com.difft.android.chat.group.mvi.GroupPageContract.Page
import com.difft.android.chat.group.mvi.GroupPageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Existing member rows, driven entirely by one retained selection set. */
abstract class GroupSelectionPageFragment : Fragment() {
    private val vm: GroupPageViewModel by viewModels()
    private var _binding: ChatActivityGroupSelectMemberBinding? = null
    private val binding get() = _binding!!
    private val wait = PageWait(this)
    private var restoringQuery = true
    private var applyingQuery = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        restoringQuery = true
        _binding = ChatActivityGroupSelectMemberBinding.inflate(inflater, container, false)
        binding.searchInput.autoFocus = false
        // SavedStateHandle owns query; framework restoration posts an older onQueryChanged.
        binding.searchInput.isSaveFromParentEnabled = false
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(view, ime = true)
        detailKeyboard(showOnFirstOpen = false, focus = { binding.searchInput.focusAndShowKeyboard() }, savedInstanceState = savedInstanceState)
        binding.title.setText(when (vm.page) {
            Page.MANAGEMENT -> R.string.group_management
            Page.MODERATORS -> when (vm.moderatorAction) {
                ModeratorAction.ADD -> R.string.group_add_moderators
                ModeratorAction.REMOVE -> R.string.group_remove_moderators
                ModeratorAction.TRANSFER -> R.string.group_transfer_owner
            }
            Page.MEMBERS -> if (vm.memberAction == MemberAction.ADD) R.string.group_add_member else R.string.group_remove_member
        })
        val adapter = object : GroupMembersAdapter(GroupSelectMemberActivity.TYPE_ADD_MEMBER) {
            override fun isMemberSelected(model: GroupMemberModel): Boolean =
                if (model.checkBoxEnable) model.uid in vm.state.value.selectedIds else model.isSelected

            override fun onMemberClicked(model: GroupMemberModel, position: Int) {
                model.uid?.let { uid ->
                    hideDetailKeyboardBeforeLeaving()
                    detailNavigation().push(this@GroupSelectionPageFragment,
                        ContactDetailDestination(uid, FriendSourceType.FROM_GROUP, vm.gid))
                }
            }
            override fun onCheckBoxClicked(model: GroupMemberModel, position: Int) {
                model.uid?.let { vm.dispatch(Intent.Select(it)) }
                // Checkbox taps mutate the widget before the VM can reject; always restore from VM truth.
                notifyItemChanged(position)
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        binding.recyclerviewContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerviewContacts.adapter = adapter
        binding.recyclerviewContacts.itemAnimator = null
        binding.searchInput.query = vm.state.value.query
        binding.searchInput.onQueryChanged = { if (!restoringQuery && !applyingQuery) vm.dispatch(Intent.Search(it.trim())) }
        binding.searchInput.onClear = { if (!restoringQuery && !applyingQuery) vm.dispatch(Intent.Search("")) }
        binding.doneButton.setOnClickListener {
            if (vm.state.value.pending != null) vm.dispatch(Intent.Retry)
            else if (vm.page == Page.MODERATORS && vm.moderatorAction == ModeratorAction.TRANSFER) {
                if (!isVisibleTopNow() || !detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@setOnClickListener
                val selected = vm.state.value.selectedIds.singleOrNull() ?: return@setOnClickListener
                val name = vm.state.value.allRows.firstOrNull { it.uid == selected }?.name.orEmpty()
                val confirmation = dismissWithView(ComposeDialogManager.showMessageDialog(
                    context = requireActivity(), title = getString(R.string.tip),
                    message = getString(R.string.group_transfer_owner_tips, name),
                    confirmText = getString(R.string.chat_dialog_ok), cancelText = getString(R.string.chat_dialog_cancel),
                    onConfirm = {
                        if (isVisibleTopNow() && detailBackOwner().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                            vm.dispatch(Intent.Submit)
                        }
                    },
                ))
                dismissWhenLeavingVisibleTop { confirmation?.dismiss() }
            } else vm.dispatch(Intent.Submit)
        }
        waitWhileVisibleTop(vm.state.map { it.busy }, wait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    if (binding.searchInput.query != state.query) {
                        applyingQuery = true
                        try { binding.searchInput.query = state.query } finally { applyingQuery = false }
                    }
                    adapter.submitList(state.rows)
                    binding.doneButton.isEnabled = !state.busy && (state.pending != null || (state.allowed && state.selectedIds.isNotEmpty() && state.completionPopCount == null))
                    binding.doneButton.setText(if (state.pending != null) R.string.chat_translate_retry else R.string.group_operation_done)
                    binding.doneButton.setTextColor(ContextCompat.getColor(requireContext(),
                        if (binding.doneButton.isEnabled) com.difft.android.base.R.color.t_info else com.difft.android.base.R.color.t_disable))
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.map { it.completionPopCount }.distinctUntilChanged().collect { count ->
                    if (count != null) {
                        hideDetailKeyboardBeforeLeaving()
                        // Keep completion in the VM until the page is actually removed. A fold can
                        // destroy a queued navigation request after it was accepted by the host.
                        detailNavigation().pop(this@GroupSelectionPageFragment, count)
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.effect.collect { effect ->
                    when (effect) {
                        Effect.CloseSubject -> detailNavigation().closeConversation(this@GroupSelectionPageFragment)
                        is Effect.Message -> ToastUtil.show(effect.resolve(this@GroupSelectionPageFragment))
                    }
                }
            }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.searchInput.query = vm.state.value.query
        restoringQuery = false
    }

    override fun onResume() {
        super.onResume()
        vm.dispatch(Intent.Load)
    }

    override fun onDestroyView() {
        binding.recyclerviewContacts.adapter = null
        binding.searchInput.onQueryChanged = {}
        binding.searchInput.onClear = {}
        _binding = null
        super.onDestroyView()
    }
}

@AndroidEntryPoint
class GroupModeratorsFragment : GroupSelectionPageFragment() {
    companion object {
        fun newInstance(gid: String, action: ModeratorAction) = GroupModeratorsFragment().apply {
            arguments = groupPageArguments(gid, Page.MODERATORS, action = action)
        }
    }
}

@AndroidEntryPoint
class GroupSelectMemberFragment : GroupSelectionPageFragment() {
    companion object {
        fun newInstance(gid: String, type: MemberAction) = GroupSelectMemberFragment().apply {
            arguments = groupPageArguments(gid, Page.MEMBERS, type = type)
        }
    }
}

/** Resource-backed messages win over server text; the default depends on whether the change was accepted. */
internal fun Effect.Message.resolve(page: Fragment): String = when {
    messageRes != null -> page.getString(messageRes, messageArg.orEmpty())
    text != null -> text
    accepted -> page.getString(R.string.group_change_followup_pending)
    else -> page.getString(R.string.operation_failed)
}
