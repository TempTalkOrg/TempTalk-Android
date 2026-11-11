package com.difft.android.chat.group.create

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneHost
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.UncoverableSearchPage
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailBackOwner
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.hideDetailKeyboardBeforeLeaving
import com.difft.android.base.utils.pane.isInDetailStack
import com.difft.android.base.utils.pane.ownsWindow
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ToastUtil
import com.difft.android.base.widget.floatAsTopBarTextButton
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickLauncher
import com.difft.android.chat.databinding.ChatActivityCreateGroupBinding
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.chat.group.GroupMembersAdapter
import com.difft.android.chat.group.GroupSelectMemberActivity
import com.difft.android.chat.group.create.CreateGroupContract.Effect
import com.difft.android.chat.group.create.CreateGroupContract.Intent
import com.difft.android.chat.recent.ConversationNavigationCallback
import com.difft.android.chat.recent.DualPaneSelectionListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch









@AndroidEntryPoint
class CreateGroupFragment : Fragment(), DualPaneSelectionListener, UncoverableSearchPage {
    private val vm: CreateGroupViewModel by viewModels()
    private var _binding: ChatActivityCreateGroupBinding? = null
    private val binding get() = _binding!!
    private val wait = PageWait(this)
    private val avatarPicker = AvatarPickLauncher.forFragment(this) { path -> vm.dispatch(Intent.AvatarPicked(path)) }
    private var restoringText = true
    private var applyingText = false
    private val selection = MutableStateFlow<String?>(null)
    private val resumeTick = MutableStateFlow(0)
    private var openRequested = false

    private val host: ConversationNavigationCallback? get() = activity as? ConversationNavigationCallback

    
    private val inListLayer: Boolean get() = arguments?.getBoolean(ARG_IN_LIST_LAYER) == true

    
    private val covered: StateFlow<Boolean> by lazy {
        if (inListLayer) host?.conversationCoversList ?: MutableStateFlow(false) else MutableStateFlow(false)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        restoringText = true
        _binding = ChatActivityCreateGroupBinding.inflate(inflater, container, false)
        binding.searchInput.autoFocus = false
        
        binding.searchInput.isSaveFromParentEnabled = false
        binding.groupNameEdit.isSaveFromParentEnabled = false
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        if (ownsWindow()) requireActivity().window.setBackgroundDrawableResource(com.difft.android.base.R.color.bg1)
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(view, ime = true)
        detailKeyboard(showOnFirstOpen = false, focus = { binding.groupNameEdit.focusAndShowKeyboard() }, savedInstanceState = savedInstanceState)
        
        binding.createButton.floatAsTopBarTextButton(leading = false)
        binding.createButton.setOnClickListener { vm.dispatch(Intent.Submit) }
        binding.groupAvatar.setOnClickListener { vm.dispatch(Intent.PickAvatar) }

        val adapter = object : GroupMembersAdapter(GroupSelectMemberActivity.TYPE_ADD_MEMBER) {
            override fun isMemberSelected(model: GroupMemberModel): Boolean = model.isSelected
            override fun onMemberClicked(model: GroupMemberModel, position: Int) = Unit
            override fun onCheckBoxClicked(model: GroupMemberModel, position: Int) {
                model.uid?.let { vm.dispatch(Intent.Toggle(it)) }
                
                notifyItemChanged(position)
                if (binding.searchInput.query.isNotEmpty()) binding.searchInput.clear()
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
        binding.recyclerviewContacts.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerviewContacts.adapter = adapter
        binding.recyclerviewContacts.itemAnimator = null

        binding.searchInput.query = vm.state.value.query
        binding.searchInput.onQueryChanged = { if (!restoringText && !applyingText) vm.dispatch(Intent.Search(it)) }
        binding.searchInput.onClear = { if (!restoringText && !applyingText) vm.dispatch(Intent.Search("")) }
        binding.groupNameEdit.query = vm.state.value.name
        binding.groupNameEdit.onQueryChanged = { if (!restoringText && !applyingText) vm.dispatch(Intent.NameChanged(it)) }
        binding.groupNameEdit.onClear = { if (!restoringText && !applyingText) vm.dispatch(Intent.NameChanged("")) }

        waitWhileVisibleTop(combine(vm.state, covered) { s, c -> s.busy && !c }, wait)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    applyText(binding.searchInput.query, state.query) { binding.searchInput.query = it }
                    applyText(binding.groupNameEdit.query, state.name) { binding.groupNameEdit.query = it }
                    adapter.submitList(state.rows)
                    if (state.avatarPath != null) binding.groupAvatar.setAvatar(state.avatarPath) else binding.groupAvatar.setAvatar(null)
                    binding.createButton.isEnabled = !state.busy && !state.avatarAdopting && state.completed == null
                    binding.createButton.setTextColor(ContextCompat.getColor(requireContext(),
                        if (binding.createButton.isEnabled) com.difft.android.base.R.color.primary else com.difft.android.base.R.color.t_disable))
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(vm.state.map { it.completed }.distinctUntilChanged(), covered, selection, resumeTick) { gid, c, sel, _ -> Triple(gid, c, sel) }
                    .collect { (gid, isCovered, selected) -> if (gid != null) onCompleted(gid, isCovered, selected) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            detailBackOwner().repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.effect.collect { effect ->
                    when (effect) {
                        Effect.RequestPickAvatar -> avatarPicker.launch()
                        is Effect.Toast -> ToastUtil.show(
                            effect.text ?: effect.messageRes?.let { if (effect.arg != null) getString(it, effect.arg) else getString(it) } ?: return@collect
                        )
                    }
                }
            }
        }
    }

    






    private fun onCompleted(gid: String, isCovered: Boolean, selected: String?) {
        val hosted = isInDetailStack()
        if (!openRequested && (!inListLayer || !isCovered)) {
            openRequested = true
            hideDetailKeyboardBeforeLeaving()
            L.i { "[CreateGroup] open gid=$gid hosted=$hosted layer=$inListLayer" }
            GroupChatContentActivity.startActivity(requireActivity(), gid)
            if (!hosted) {
                detailNavigation().pop(this)
                return
            }
        }
        
        if (!inListLayer) return
        val shown = if ((activity as? DualPaneHost)?.isDualPaneMode == true) selected == gid
        else isCovered && host?.currentConversationOnScreen() == gid
        if (shown) detailNavigation().pop(this)
    }

    private inline fun applyText(current: String, wanted: String, set: (String) -> Unit) {
        if (current == wanted) return
        applyingText = true
        try { set(wanted) } finally { applyingText = false }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding.searchInput.query = vm.state.value.query
        binding.groupNameEdit.query = vm.state.value.name
        restoringText = false
    }

    override fun onResume() {
        super.onResume()
        resumeTick.value++
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        selection.value = selectedId
    }

    
    override fun onCovered() {
        _binding?.searchInput?.hideKeyboard()
        _binding?.groupNameEdit?.hideKeyboard()
    }

    override fun onUncovered() = Unit

    override fun onDestroyView() {
        binding.recyclerviewContacts.adapter = null
        binding.searchInput.onQueryChanged = {}
        binding.searchInput.onClear = {}
        binding.groupNameEdit.onQueryChanged = {}
        binding.groupNameEdit.onClear = {}
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_IN_LIST_LAYER = "in_list_layer"

        
        fun newInstance(preselectedIds: List<String>): CreateGroupFragment = CreateGroupFragment().apply {
            arguments = bundleOf(CreateGroupViewModel.ARG_PRESELECTED to ArrayList(preselectedIds.distinct()))
        }

        
        fun newInstance(layerRootArgs: Bundle?): CreateGroupFragment = CreateGroupFragment().apply {
            arguments = bundleOf(
                CreateGroupViewModel.ARG_PRESELECTED to ArrayList(preselectedIds(layerRootArgs)),
                ARG_IN_LIST_LAYER to true,
            )
        }

        
        fun rootArgs(preselectedIds: List<String>): Bundle =
            bundleOf(CreateGroupViewModel.ARG_PRESELECTED to ArrayList(preselectedIds.distinct()))

        fun preselectedIds(args: Bundle?): List<String> =
            args?.getStringArrayList(CreateGroupViewModel.ARG_PRESELECTED).orEmpty()
    }
}
