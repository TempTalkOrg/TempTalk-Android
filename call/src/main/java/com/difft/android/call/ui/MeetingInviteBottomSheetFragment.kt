package com.difft.android.call.ui

import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.handler.InviteCallHandler
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject


/**
 * 会议邀请底部弹窗 Fragment
 * 参考 ChatSelectBottomSheetFragment 的实现方式
 */
@AndroidEntryPoint
class MeetingInviteBottomSheetFragment : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    private var inviteCallHandler: InviteCallHandler? = null
    var onInviteAction: ((InviteViewState) -> Unit)? = null
    
    // 保存 TextWatcher 引用以便在销毁时移除
    private var searchTextWatcher: android.text.TextWatcher? = null
    private var searchInput: AppCompatEditText? = null
    private var previousNightMode: Int? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (activity as? AppCompatActivity)?.let { appCompatActivity ->
            val delegate = appCompatActivity.delegate
            previousNightMode = delegate.localNightMode
            delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        }
    }

    companion object {
        private const val ARG_HANDLER_ID = "arg_handler_id"

        fun newInstance(handlerId: String? = null): MeetingInviteBottomSheetFragment {
            return MeetingInviteBottomSheetFragment().apply {
                arguments = Bundle().apply {
                    handlerId?.let { putString(ARG_HANDLER_ID, it) }
                }
            }
        }
    }

    // 使用默认容器（带圆角和拖拽条）
    override fun getContentLayoutResId(): Int = R.layout.layout_meeting_invite_bottom_sheet

    // 全屏显示
    override fun isFullScreen(): Boolean = true

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        val screenHeight = activity?.let { WindowSizeClassUtil.getWindowHeightPx(it) }
            ?: resources.displayMetrics.heightPixels
        behavior.isFitToContents = false
        behavior.skipCollapsed = true
        behavior.expandedOffset = 0
        behavior.peekHeight = screenHeight
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
    }

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        // 覆盖默认容器背景色，并确保隐藏拖拽条
        requireView().setBackgroundResource(R.drawable.bg_meeting_invite_bottom_sheet)
        requireView().findViewById<View>(com.difft.android.base.R.id.drag_handle)?.apply {
            setBackgroundColor(ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.bgpage_primary))
            visibility = View.GONE
        }

        // 获取 InviteCallHandler - 这里需要通过参数传递或从 Manager 获取
        // 暂时先通过回调设置
        if (inviteCallHandler == null) {
            L.w { "[MeetingInvite] InviteCallHandler is null, fragment will not work properly" }
            dismiss()
            return
        }

        setupInitialScreen(view)
        setupAddMembersScreen(view)
        
        // 监听状态变化
        observeStateChanges(view)
        
        // 初始加载联系人
        inviteCallHandler?.loadContacts()
    }

    fun setInviteCallHandler(handler: InviteCallHandler) {
        this.inviteCallHandler = handler
    }

    private fun setupInitialScreen(view: View) {
        val ivClose = view.findViewById<android.widget.ImageView>(R.id.iv_close_initial)
        val tvInvite = view.findViewById<AppCompatTextView>(R.id.tv_invite)
        val tvAdd = view.findViewById<AppCompatTextView>(R.id.tv_add_member)
        val llAddMember = view.findViewById<ViewGroup>(R.id.ll_add_member)
        val recyclerViewMembers = view.findViewById<RecyclerView>(R.id.recyclerview_selected_members)

        ivClose?.setOnClickListener {
            onInviteAction?.invoke(InviteViewState.DISMISS)
            dismiss()
        }

        llAddMember?.setOnClickListener {
            inviteCallHandler?.let { handler ->
                handler.updateFilteredContacts()
                handler.setCurrentState(InviteScreenState.ADD_MEMBERS)
            }
        }

        tvInvite?.setOnClickListener {
            inviteCallHandler?.let { handler ->
                if (handler.selectedMembers.value.isNotEmpty()) {
                    onInviteAction?.invoke(InviteViewState.INVITE)
                }
            }
        }

        // 设置已选成员列表
        val membersAdapter = MeetingInviteMembersAdapter(
            onRemoveClick = { member ->
                inviteCallHandler?.removeMember(member)
                inviteCallHandler?.updateFilteredContacts()
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        recyclerViewMembers?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = membersAdapter
        }

        // 监听已选成员变化
        inviteCallHandler?.selectedMembers?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { members ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        membersAdapter.submitList(members) {
                            recyclerViewMembers?.scrollToPosition(0)
                        }
                        // 更新邀请按钮状态
                        tvInvite?.isEnabled = members.isNotEmpty()
                        tvInvite?.alpha = if (members.isNotEmpty()) 1.0f else 0.5f
                    }
                }
            }
        }
    }

    private fun setupAddMembersScreen(view: View) {
        val ivClose = view.findViewById<android.widget.ImageView>(R.id.iv_close_add)
        val tvDone = view.findViewById<AppCompatTextView>(R.id.tv_done)
        val btnClear = view.findViewById<AppCompatImageButton>(R.id.button_clear)
        val etSearch = view.findViewById<AppCompatEditText>(R.id.edittext_search_input)
        val recyclerViewSelectedStrip = view.findViewById<RecyclerView>(R.id.recyclerview_selected_strip)
        val recyclerViewContacts = view.findViewById<RecyclerView>(R.id.recyclerview_contacts)
        val tvNoResult = view.findViewById<AppCompatTextView>(R.id.tv_no_result)
        searchInput = etSearch

        ivClose?.setOnClickListener {
            onInviteAction?.invoke(InviteViewState.DISMISS)
            dismiss()
        }

        tvDone?.setOnClickListener {
            inviteCallHandler?.let { handler ->
                val selectedIds = handler.selectedContactIds.value
                if (selectedIds.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        handler.addSelectedContacts(selectedIds.toList())
                        onInviteAction?.invoke(InviteViewState.INVITE)
                    }
                }
            }
        }

        btnClear?.setOnClickListener {
            etSearch?.text = null
        }

        searchTextWatcher = etSearch?.addTextChangedListener {
            val keyword = it.toString().trim()
            inviteCallHandler?.searchContacts(keyword)
            resetButtonClear(btnClear, keyword)
        }

        val selectedStripAdapter = MeetingInviteSelectedParticipantsAdapter(
            onParticipantClick = { contactId ->
                inviteCallHandler?.let { handler ->
                    val currentSelected = handler.selectedContactIds.value.toMutableSet()
                    if (currentSelected.remove(contactId)) {
                        handler.setSelectedContactIds(currentSelected)
                    }
                }
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        recyclerViewSelectedStrip?.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = selectedStripAdapter
            isNestedScrollingEnabled = false
        }

        var contactsSnapshot: List<ContactorModel> = emptyList()
        var selectedIdsSnapshot: Set<String> = emptySet()

        fun updateSelectedStrip() {
            val selectedContacts = contactsSnapshot.filter { selectedIdsSnapshot.contains(it.id) }
            val sortedSelected = callToChatController.contactorListSortedByPinyin(selectedContacts)
            val items = sortedSelected.map { contact ->
                MeetingInviteSelectedParticipantItem(contact)
            }
            selectedStripAdapter.submitList(items) {
                recyclerViewSelectedStrip?.scrollToPosition(0)
            }
            recyclerViewSelectedStrip?.visibility = if (items.isNotEmpty()) View.VISIBLE else View.GONE
        }

        // 设置联系人列表
        val contactsAdapter = MeetingInviteContactsAdapter(
            onContactClick = { contactId ->
                inviteCallHandler?.let { handler ->
                    val currentSelected = handler.selectedContactIds.value.toMutableSet()
                    if (currentSelected.contains(contactId)) {
                        currentSelected.remove(contactId)
                    } else {
                        currentSelected.add(contactId)
                    }
                    handler.setSelectedContactIds(currentSelected)
                }
            },
            lifecycleScope = viewLifecycleOwner.lifecycleScope
        )
        recyclerViewContacts?.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = contactsAdapter
            isNestedScrollingEnabled = true
            // 设置 clipToPadding 为 false，允许内容滚动到 padding 区域
            clipToPadding = false
            // 动态设置底部 padding，确保最后一项可以完全显示
            // 由于设置了 expandedOffset，需要添加额外的底部 padding
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val topOffsetDp = if (isLandscape) 40 else 80
            val bottomPaddingPx = (topOffsetDp * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPaddingPx)
        }

        // 监听过滤后的联系人变化
        inviteCallHandler?.filteredContacts?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { contacts ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        
                        val selectedIds = inviteCallHandler?.selectedContactIds?.value ?: emptySet()
                        val inMeetingIds = inviteCallHandler?.inMeetingIds?.value ?: emptySet()
                        val contactsList = callToChatController.contactorListSortedByPinyin(contacts).map { contact ->
                            MeetingInviteContactItem(
                                contact = contact,
                                isSelected = selectedIds.contains(contact.id),
                                isInMeeting = inMeetingIds.contains(contact.id)
                            )
                        }

                        if (contactsList.isEmpty()) {
                            tvNoResult?.visibility = View.VISIBLE
                            recyclerViewContacts?.visibility = View.GONE
                        } else {
                            tvNoResult?.visibility = View.GONE
                            recyclerViewContacts?.visibility = View.VISIBLE
                            contactsAdapter.submitList(contactsList) {
                                recyclerViewContacts?.scrollToPosition(0)
                            }
                        }
                    }
                }
            }
        }

        inviteCallHandler?.contacts?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { contacts ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        contactsSnapshot = contacts
                        updateSelectedStrip()
                    }
                }
            }
        }

        // 监听已选联系人ID变化，更新适配器
        inviteCallHandler?.selectedContactIds?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { selectedIds ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        selectedIdsSnapshot = selectedIds
                        updateSelectedStrip()
                        contactsAdapter.updateSelectedIds(selectedIds)
                        // 更新完成按钮状态
                        tvDone?.isEnabled = selectedIds.isNotEmpty()
                        tvDone?.alpha = if (selectedIds.isNotEmpty()) 1.0f else 0.5f
                    }
                }
            }
        }
    }

    private fun observeStateChanges(view: View) {
        val containerInitial = view.findViewById<ViewGroup>(R.id.container_initial)
        val containerAddMembers = view.findViewById<ViewGroup>(R.id.container_add_members)

        inviteCallHandler?.currentState?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { state ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        when (state) {
                            InviteScreenState.INITIAL, InviteScreenState.CONFIRM -> {
                                containerInitial?.visibility = View.VISIBLE
                                containerAddMembers?.visibility = View.GONE
                                searchInput?.let { input ->
                                    input.clearFocus()
                                    hideKeyboard(input)
                                }
                            }
                            InviteScreenState.ADD_MEMBERS -> {
                                containerInitial?.visibility = View.GONE
                                containerAddMembers?.visibility = View.VISIBLE
                                searchInput?.let { input ->
                                    input.clearFocus()
                                    hideKeyboard(input)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resetButtonClear(btnClear: AppCompatImageButton?, searchKey: String) {
        btnClear?.animate()?.apply {
            cancel()
            val toAlpha = if (!TextUtils.isEmpty(searchKey)) 1.0f else 0f
            alpha(toAlpha)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 移除 TextWatcher 防止内存泄漏
        searchTextWatcher?.let { watcher ->
            view?.findViewById<AppCompatEditText>(R.id.edittext_search_input)?.removeTextChangedListener(watcher)
        }
        searchTextWatcher = null
        searchInput = null
        onInviteAction = null
        inviteCallHandler = null
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        super.onDismiss(dialog)
        L.i { "[Call] MeetingInvite onDismiss" }
        (activity as? AppCompatActivity)?.let { appCompatActivity ->
            previousNightMode?.let { mode ->
                appCompatActivity.delegate.localNightMode = mode
            }
        }
        onInviteAction?.invoke(InviteViewState.DISMISS)
    }

    private fun showKeyboard(editText: AppCompatEditText) {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        editText.post {
            imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}

