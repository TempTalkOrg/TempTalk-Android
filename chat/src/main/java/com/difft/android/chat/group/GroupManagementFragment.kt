package com.difft.android.chat.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.difft.android.base.ui.TitleBar
import com.difft.android.base.ui.theme.DifftTheme
import com.difft.android.base.utils.pane.SettingsIdiomPage
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.contacts.contactsdetail.ContactDetailDestination
import com.difft.android.chat.contacts.data.FriendSourceType
import com.difft.android.chat.databinding.ActivityGroupManagementBinding
import com.difft.android.chat.group.mvi.GroupPageContract.Effect
import com.difft.android.chat.group.mvi.GroupPageContract.Intent
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import com.difft.android.chat.group.mvi.GroupPageContract.Page
import com.difft.android.chat.group.mvi.GroupPageContract.Setting
import com.difft.android.chat.group.mvi.GroupPageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class GroupManagementFragment : Fragment(), SettingsIdiomPage {
    private val vm: GroupPageViewModel by viewModels()
    private var _binding: ActivityGroupManagementBinding? = null
    private val binding get() = _binding!!
    private val wait = PageWait(this)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityGroupManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        applyDetailPageInsets(view)
        binding.composeTitleBar.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeTitleBar.setContent {
            val state by vm.state.collectAsState()
            DifftTheme(applyWindowBackground = false) {
                Column {
                    TitleBar(titleText = getString(R.string.group_management),
                        onBackClick = { detailNavigation().pop(this@GroupManagementFragment) })
                    if (state.pending != null) {
                        Text(getString(R.string.group_change_followup_pending), color = DifftTheme.colors.textPrimary,
                            style = DifftTheme.typography.p2)
                        TextButton(enabled = !state.busy, onClick = { vm.dispatch(Intent.Retry) }) {
                            Text(getString(R.string.chat_translate_retry), color = DifftTheme.colors.textPrimary)
                        }
                    }
                }
            }
        }
        val adapter = object : GroupInfoMemberAdapter() {
            override fun onItemClick(contact: GroupMemberModel) {
                if (!vm.state.value.allowed || vm.state.value.busy || vm.state.value.pending != null) return
                when (contact.uid) {
                    "+" -> detailNavigation().push(this@GroupManagementFragment,
                        GroupModeratorsDestination(vm.gid, ModeratorAction.ADD))
                    "-" -> detailNavigation().push(this@GroupManagementFragment,
                        GroupModeratorsDestination(vm.gid, ModeratorAction.REMOVE))
                    else -> contact.uid?.let { detailNavigation().push(this@GroupManagementFragment,
                        ContactDetailDestination(it, FriendSourceType.FROM_GROUP, vm.gid)) }
                }
            }
        }
        binding.rvModerators.layoutManager = GridLayoutManager(requireContext(), 5)
        binding.rvModerators.adapter = adapter
        binding.rvModerators.itemAnimator = null
        binding.clTransferOwner.setOnClickListener {
            if (vm.state.value.allowed && !vm.state.value.busy && vm.state.value.pending == null) {
                detailNavigation().push(this, GroupModeratorsDestination(vm.gid, ModeratorAction.TRANSFER))
            }
        }
        binding.switchCanAdd.setOnToggleRequestListener { _, checked -> vm.dispatch(Intent.ChangeSetting(Setting.INVITATION, checked)) }
        binding.switchCanSpeak.setOnToggleRequestListener { _, checked -> vm.dispatch(Intent.ChangeSetting(Setting.PUBLISH, checked)) }
        binding.switchCanCriticalAlert.setOnToggleRequestListener { _, checked -> vm.dispatch(Intent.ChangeSetting(Setting.CRITICAL_ALERT, checked)) }
        waitWhileVisibleTop(vm.state.map { it.busy }, wait)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    binding.switchCanAdd.isChecked = state.invitation
                    binding.switchCanSpeak.isChecked = state.publish
                    binding.switchCanCriticalAlert.isChecked = state.criticalAlert
                    val enabled = state.allowed && !state.busy && state.pending == null
                    binding.switchCanAdd.isEnabled = enabled
                    binding.switchCanSpeak.isEnabled = enabled
                    binding.switchCanCriticalAlert.isEnabled = enabled
                    binding.clTransferOwner.isEnabled = enabled
                    adapter.submitList(state.rows)
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.effect.collect { effect ->
                    when (effect) {
                        Effect.CloseSubject -> detailNavigation().closeConversation(this@GroupManagementFragment)
                        is Effect.Message -> ToastUtil.show(effect.resolve(this@GroupManagementFragment))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.dispatch(Intent.Load)
    }

    override fun onDestroyView() {
        binding.rvModerators.adapter = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        fun newInstance(gid: String) = GroupManagementFragment().apply {
            arguments = groupPageArguments(gid, Page.MANAGEMENT)
        }
    }
}
