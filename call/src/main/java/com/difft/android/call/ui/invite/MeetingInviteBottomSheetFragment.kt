package com.difft.android.call.ui.invite

import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.ViewGroup
import android.view.WindowManager
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
import com.difft.android.base.ui.theme.tokens.ColorTokens
import com.difft.android.base.utils.WindowSizeClassUtil
import com.difft.android.base.utils.hideNavigationBar
import com.difft.android.base.utils.hideSystemBars
import com.difft.android.base.widget.floatAsTopBarButton
import com.difft.android.call.LCallActivity
import com.difft.android.base.widget.BaseBottomSheetDialogFragment
import com.difft.android.call.LCallToChatController
import com.difft.android.call.R
import com.difft.android.call.handler.InviteCallHandler
import com.google.android.material.bottomsheet.BottomSheetBehavior
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.models.ContactorModel
import javax.inject.Inject





@AndroidEntryPoint
class MeetingInviteBottomSheetFragment : BaseBottomSheetDialogFragment() {

    @Inject
    lateinit var callToChatController: LCallToChatController

    private var inviteCallHandler: InviteCallHandler? = null
    var onInviteAction: ((InviteViewState) -> Unit)? = null
    
    
    private var searchTextWatcher: android.text.TextWatcher? = null
    private var searchInput: AppCompatEditText? = null
    private var previousNightMode: Int? = null

    
    private var dockHeightPx: Int? = null

    
    private var statusBarHidden = false
    private var sheetView: View? = null
    private var sheetBehavior: BottomSheetBehavior<*>? = null
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

    override fun getContentLayoutResId(): Int = R.layout.layout_meeting_invite_bottom_sheet

    
    
    override fun isFullScreen(): Boolean = dockHeightPx == null

    override fun onStart() {
        super.onStart()
        applySystemBars()
        
        
        
        if (dockHeightPx == null) return
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(sheet)
        sheetView = sheet
        sheetBehavior = behavior
        applySheetHeight(sheet, behavior)
        
        
        
        val decor = dialog?.window?.decorView ?: return
        if (decor.isAttachedToWindow) return
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                sheetView?.let { reachWindowBottom(it, dockHeightPx != null) }
            }

            override fun onViewDetachedFromWindow(v: View) = Unit
        })
    }

    override fun onBottomSheetReady(sheet: View, behavior: BottomSheetBehavior<*>) {
        sheetView = sheet
        sheetBehavior = behavior
        applySheetHeight(sheet, behavior)
    }

    
    fun setStatusBarHidden(hidden: Boolean) {
        if (statusBarHidden == hidden) return
        statusBarHidden = hidden
        if (dialog?.window != null) applySystemBars()
    }

    



    private fun applySystemBars() {
        val window = dialog?.window ?: return
        val immersive = statusBarHidden || (activity as? LCallActivity)?.screenShareOrientationActive == true
        if (immersive) {
            window.hideSystemBars()
        } else {
            window.hideNavigationBar()
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.statusBars())
        }
    }

    



    fun setDockHeight(px: Int?) {
        if (dockHeightPx == px) return
        dockHeightPx = px
        val sheet = sheetView ?: return
        val behavior = sheetBehavior ?: return
        applySheetHeight(sheet, behavior)
    }

    private fun applySheetHeight(sheet: View, behavior: BottomSheetBehavior<*>) {
        val screenHeight = activity?.let { WindowSizeClassUtil.getWindowHeightPx(it) }
            ?: resources.displayMetrics.heightPixels
        val dockHeight = dockHeightPx?.takeIf { it in 1 until screenHeight }
        behavior.skipCollapsed = true
        if (dockHeight != null) {
            
            sheet.layoutParams = sheet.layoutParams.apply { height = dockHeight }
            behavior.isFitToContents = true
            behavior.peekHeight = dockHeight
            dialog?.window?.setDimAmount(0f)
        } else {
            sheet.layoutParams = sheet.layoutParams.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            behavior.isFitToContents = false
            behavior.expandedOffset = 0
            behavior.peekHeight = screenHeight
            dialog?.window?.setDimAmount(ColorTokens.ScrimDimAmount)
        }
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        val docked = dockHeight != null
        reachWindowBottom(sheet, docked)
        val heightPx = dockHeight ?: screenHeight
        L.i { "[MeetingInvite] sheetHeight docked=$docked heightPx=$heightPx" }
    }

    
    private var undockedInsets: UndockedInsets? = null

    




    private class UndockedInsets(val fitInsetsTypes: Int, val softInputMode: Int) {
        var parentsFit: List<Boolean>? = null
        var parentsPadding: List<IntArray>? = null
    }

    





    
    override fun reservesKeyboardInset(): Boolean = undockedInsets == null

    private fun reachWindowBottom(sheet: View, docked: Boolean) {
        val window = dialog?.window ?: return
        val parents = generateSequence(sheet.parent as? View) { it.parent as? View }.take(2).toList()
        if (docked) {
            val saved = undockedInsets ?: UndockedInsets(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.attributes.fitInsetsTypes else 0,
                window.attributes.softInputMode,
            ).also { undockedInsets = it }
            if (saved.parentsFit == null && window.decorView.isAttachedToWindow) {
                saved.parentsFit = parents.map { it.fitsSystemWindows }
                saved.parentsPadding = parents.map { intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom) }
            }
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = 0 }
            }
            
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            parents.forEach {
                it.fitsSystemWindows = false
                it.setPadding(0, 0, 0, 0)
            }
            
            
            
            
            parents.lastOrNull()?.let { container ->
                ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
                    view.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
                    insets
                }
                ViewCompat.requestApplyInsets(container)
            }
            view?.let(ViewCompat::requestApplyInsets)
        } else {
            val saved = undockedInsets ?: return
            undockedInsets = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply { fitInsetsTypes = saved.fitInsetsTypes }
            }
            window.setSoftInputMode(saved.softInputMode)
            parents.lastOrNull()?.let { ViewCompat.setOnApplyWindowInsetsListener(it, null) }
            view?.let(ViewCompat::requestApplyInsets)
            
            val fits = saved.parentsFit ?: return
            val paddings = saved.parentsPadding ?: return
            
            WindowCompat.setDecorFitsSystemWindows(window, fits.lastOrNull() ?: true)
            parents.forEachIndexed { i, view ->
                view.fitsSystemWindows = fits[i]
                paddings[i].let { (l, t, r, b) -> view.setPadding(l, t, r, b) }
            }
            parents.firstOrNull()?.let(ViewCompat::requestApplyInsets)
        }
    }

    override fun onContentViewCreated(view: View, savedInstanceState: Bundle?) {
        requireView().setBackgroundResource(R.drawable.bg_meeting_invite_bottom_sheet)

        if (inviteCallHandler == null) {
            L.w { "[MeetingInvite] InviteCallHandler is null, fragment will not work properly" }
            dismiss()
            return
        }

        setupInitialScreen(view)
        setupAddMembersScreen(view)
        
        observeStateChanges(view)
        
        inviteCallHandler?.loadContacts()
    }

    fun setInviteCallHandler(handler: InviteCallHandler) {
        this.inviteCallHandler = handler
    }

    
    private fun android.widget.ImageView.floatAsCallSheetClose() {
        floatAsTopBarButton(leading = true, fillColor = ContextCompat.getColor(context, com.difft.android.base.R.color.bg3_night))
    }

    private fun setupInitialScreen(view: View) {
        val ivClose = view.findViewById<android.widget.ImageView>(R.id.iv_close_initial)
        ivClose.floatAsCallSheetClose()
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

        inviteCallHandler?.selectedMembers?.let { flow ->
            viewLifecycleOwner.lifecycleScope.launch {
                flow.collect { members ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        membersAdapter.submitList(members) {
                            recyclerViewMembers?.scrollToPosition(0)
                        }
                        tvInvite?.isEnabled = members.isNotEmpty()
                        tvInvite?.alpha = if (members.isNotEmpty()) 1.0f else 0.5f
                    }
                }
            }
        }
    }

    private fun setupAddMembersScreen(view: View) {
        val ivClose = view.findViewById<android.widget.ImageView>(R.id.iv_close_add)
        ivClose.floatAsCallSheetClose()
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
            clipToPadding = false
            
            val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val topOffsetDp = if (isLandscape) 40 else 80
            val bottomPaddingPx = (topOffsetDp * resources.displayMetrics.density).toInt()
            setPadding(0, 0, 0, bottomPaddingPx)
        }

        
        inviteCallHandler?.let { handler ->
            viewLifecycleOwner.lifecycleScope.launch {
                combine(
                    handler.filteredContacts,
                    handler.selectedContactIds,
                    handler.inMeetingIds
                ) { contacts, selectedIds, inMeetingIds ->
                    Triple(contacts, selectedIds, inMeetingIds)
                }.collect { (contacts, selectedIds, inMeetingIds) ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext

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
                            contactsAdapter.submitList(contactsList)
                        }

                        tvDone?.isEnabled = selectedIds.isNotEmpty()
                        tvDone?.alpha = if (selectedIds.isNotEmpty()) 1.0f else 0.5f
                    }
                }
            }

            viewLifecycleOwner.lifecycleScope.launch {
                combine(
                    handler.contacts,
                    handler.selectedContactIds
                ) { contacts, selectedIds ->
                    contacts to selectedIds
                }.collect { (contacts, selectedIds) ->
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        contactsSnapshot = contacts
                        selectedIdsSnapshot = selectedIds
                        updateSelectedStrip()
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
        searchTextWatcher?.let { watcher ->
            view?.findViewById<AppCompatEditText>(R.id.edittext_search_input)?.removeTextChangedListener(watcher)
        }
        searchTextWatcher = null
        searchInput = null
        onInviteAction = null
        inviteCallHandler = null
        sheetView = null
        sheetBehavior = null
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

