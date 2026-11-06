package com.difft.android.chat.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.DualPaneUtils.setupBackButton
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.ConversationTarget
import com.difft.android.base.utils.pane.DetailDestination
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.base.utils.pane.PageWait
import com.difft.android.base.utils.pane.applyDetailPageInsets
import com.difft.android.base.utils.pane.detailKeyboard
import com.difft.android.base.utils.pane.detailNavigation
import com.difft.android.base.utils.pane.dismissWhenLeavingVisibleTop
import com.difft.android.base.utils.pane.dismissWithView
import com.difft.android.base.utils.pane.launchOutlivingPage
import com.difft.android.base.utils.pane.waitWhileVisibleTop
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ActivityGroupInCommonBinding
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupUtil
import com.difft.android.network.BaseResponse
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupRepo
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.members
import org.difft.app.database.models.DBGroupMemberContactorModel
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import com.difft.android.chat.focus.invalid.FocusLeaveNotice
import com.difft.android.chat.focus.GroupFocusRepository

/**
 * Groups shared with a contact, with leave / disband / remove-the-contact actions. A page of the
 * home detail stack or of its shell [GroupInCommonActivity].
 *
 * The group writes outlive the page (a fold recreates it) under [operationKey]: a restored page
 * shows the wait while one runs; the list refreshes from the group updates it causes. A group tap
 * opens that group as a conversation (hosted: the tab's root is replaced).
 */
@AndroidEntryPoint
class GroupInCommonFragment : Fragment() {

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var groupUtil: GroupUtil

    @Inject
    lateinit var wcdb: WCDB

    @Inject
    lateinit var focusRepository: GroupFocusRepository

    private var _binding: ActivityGroupInCommonBinding? = null
    private val binding: ActivityGroupInCommonBinding get() = _binding!!

    private val contactId: String by lazy { arguments?.getString(ARG_CONTACT_ID).orEmpty() }
    private val operationKey: String get() = "group-membership:$contactId"
    private val myId: String get() = globalServices.myId

    private val writeWait = PageWait(this)
    private var searchJob: Job? = null
    private var popupWindow: PopupWindow? = null

    private val groupsAdapter: GroupInCommonAdapter by lazy {
        object : GroupInCommonAdapter() {
            override fun onItemClick(group: GroupModel) {
                val gid = group.gid ?: return
                detailNavigation().openConversation(ConversationTarget(ConversationTarget.Kind.GROUP, gid))
            }

            override fun onItemLongClick(itemView: View, group: GroupModel, touchX: Int, touchY: Int) {
                showItemActionsPop(itemView, group, touchX, touchY)
            }
        }.apply { stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = ActivityGroupInCommonBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onDestroyView() {
        popupWindow?.dismiss()
        popupWindow = null
        searchJob = null
        _binding = null
        super.onDestroyView()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (contactId.isEmpty()) {
            L.w { "[GroupInCommon] page without a contact id" }
            detailNavigation().pop(this)
            return
        }
        setupBackButton(binding.ibBack)
        applyDetailPageInsets(binding.root, ime = true)
        // The search field: no keyboard on open, hidden when the page is covered, resize lease < 30.
        detailKeyboard(showOnFirstOpen = false, focus = {}, savedInstanceState = savedInstanceState)
        waitWhileVisibleTop(PageOperations.running(operationKey), writeWait)
        // The action popup sits on the window; it must not stay over the page shown next.
        dismissWhenLeavingVisibleTop { popupWindow?.dismiss() }

        binding.recyclerviewGroup.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = groupsAdapter
            itemAnimator = null
        }

        binding.searchInput.onQueryChanged = { restartDebouncedSearch() }

        // Cancel the pending debounce, search at once, scroll the old list to top. The text itself
        // is already cleared by the component before this callback runs.
        binding.searchInput.onClear = {
            searchJob?.cancel()
            searchGroups()
            binding.recyclerviewGroup.scrollToPosition(0)
        }

        groupUtil.singleGroupsUpdate
            .onEach {
                // The external setter is silent (no callback), so the refresh is explicit. Not
                // clear(): that is the user-tap semantics (scroll to top, un-debounced search).
                binding.searchInput.query = ""
                restartDebouncedSearch()
            }
            .catch { L.w { "[GroupInCommon] observe singleGroupsUpdate error: ${it.stackTraceToString()}" } }
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .launchIn(viewLifecycleOwner.lifecycleScope)

        searchGroups()
    }

    private fun restartDebouncedSearch() {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            searchGroups()
        }
    }

    private fun searchGroups() {
        val contactId = contactId
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val searchQuery = binding.searchInput.query.trim()
                val commonGroups = withContext(Dispatchers.IO) {
                    // Groups where both users are members.
                    val contactGroups = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.id.eq(contactId)
                    ).map { it.gid }.distinct()
                    if (contactGroups.isEmpty()) return@withContext emptyList()

                    val myGroups = wcdb.groupMemberContactor.getAllObjects(
                        DBGroupMemberContactorModel.id.eq(globalServices.myId)
                    ).map { it.gid }.distinct()
                    val commonGroupIds = contactGroups.intersect(myGroups.toSet()).filter { it.matches(Regex("^[0-9a-fA-F]+$")) }.distinct()
                    if (commonGroupIds.isEmpty()) return@withContext emptyList()

                    val baseQuery = DBGroupModel.gid.`in`(*commonGroupIds.toTypedArray()).and(DBGroupModel.status.eq(0))
                    val finalQuery = if (searchQuery.isNotEmpty()) {
                        baseQuery.and(DBGroupModel.name.upper().like("%${searchQuery.uppercase()}%"))
                    } else {
                        baseQuery
                    }
                    wcdb.group.getAllObjects(finalQuery)
                }
                groupsAdapter.submitList(commonGroups) {
                    _binding?.recyclerviewGroup?.scrollToPosition(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[GroupInCommon] Error searching common groups: ${e.stackTraceToString()}" }
                groupsAdapter.submitList(emptyList())
            }
        }
    }

    private fun showItemActionsPop(rootView: View, group: GroupModel, touchX: Int, touchY: Int) {
        if (group.status != 0) return
        viewLifecycleOwner.lifecycleScope.launch {
            val members = withContext(Dispatchers.IO) { group.members }
            val myRole = members.find { it.id == myId }?.groupRole ?: return@launch
            val errorColor = ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.error)

            val itemList = mutableListOf<ChativePopupView.Item>().apply {
                if (myRole == GROUP_ROLE_OWNER) {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_disband_new), getString(R.string.group_disband), errorColor) {
                        disbandGroup(group)
                        popupWindow?.dismiss()
                    })
                } else {
                    add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_leave), getString(R.string.group_leave), errorColor) {
                        leaveGroup(group)
                        popupWindow?.dismiss()
                    })
                }

                val contactMember = members.find { it.id == contactId }
                if (contactMember != null) {
                    val contactRole = contactMember.groupRole ?: GROUP_ROLE_MEMBER
                    val canRemove = myRole < contactRole ||
                        (group.anyoneRemove == true && contactRole == GROUP_ROLE_MEMBER && contactId != myId)
                    if (canRemove) {
                        add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_remove_member), getString(R.string.group_remove_member_action), errorColor) {
                            removeMemberFromGroup(group)
                            popupWindow?.dismiss()
                        })
                    }
                }
            }
            popupWindow = ChativePopupWindow.showAtTouchPosition(rootView, itemList, touchX, touchY)
        }
    }

    /** One membership write at a time, completed even if a fold recreates the page meanwhile. */
    private fun runMembershipWrite(action: String, call: suspend () -> BaseResponse<Any>) {
        if (PageOperations.running(operationKey).value) return
        val key = operationKey
        val focus = focusRepository
        launchOutlivingPage {
            PageOperations.track(key) {
                try {
                    val response = withContext(Dispatchers.IO) { call() }
                    // Leaving or disbanding may invalidate the focus group: reconcile at once.
                    if (action != "removeMember") focus.onLocalGroupExit(response)
                    if (!response.isSuccess()) {
                        onPage { ToastUtil.show(response.reason ?: it.getString(R.string.operation_failed)) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    L.w { "[GroupInCommon] $action error: ${e.stackTraceToString()}" }
                    onPage { ToastUtil.show(R.string.chat_net_error) }
                }
            }
        }
    }

    private fun leaveGroup(group: GroupModel) {
        val gid = group.gid ?: return
        val repo = groupRepo
        val me = myId
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.group_leave),
            message = FocusLeaveNotice.message(requireContext(), gid, getString(R.string.group_leave_notice)),
            confirmText = getString(R.string.group_leave_leave),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = { runMembershipWrite("leaveGroup") { repo.leaveGroup(gid, AddOrRemoveMembersReq(mutableListOf(me))) } },
        ))
    }

    private fun disbandGroup(group: GroupModel) {
        val gid = group.gid ?: return
        val repo = groupRepo
        dismissWithView(ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.group_disband),
            message = getString(R.string.group_disband_tips),
            confirmText = getString(R.string.group_disband_disband),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = { runMembershipWrite("disbandGroup") { repo.deleteGroup(gid) } },
        ))
    }

    private fun removeMemberFromGroup(group: GroupModel) {
        val gid = group.gid ?: return
        val cid = contactId
        val repo = groupRepo
        viewLifecycleOwner.lifecycleScope.launch {
            val displayName = withContext(Dispatchers.IO) {
                wcdb.groupMemberContactor.getFirstObject(
                    DBGroupMemberContactorModel.gid.eq(gid).and(DBGroupMemberContactorModel.id.eq(cid))
                )?.displayName ?: cid
            }
            dismissWithView(ComposeDialogManager.showMessageDialog(
                context = requireActivity(),
                title = getString(R.string.group_remove_member_title, displayName),
                message = getString(R.string.group_remove_member_body),
                confirmText = getString(R.string.group_remove_member_confirm),
                cancelText = getString(R.string.group_leave_cancel),
                cancelable = false,
                onConfirm = { runMembershipWrite("removeMember") { repo.removeMembers(gid, listOf(cid)) } },
            ))
        }
    }

    companion object {
        private const val ARG_CONTACT_ID = "id"
        private const val SEARCH_DEBOUNCE_MS = 300L

        fun arguments(contactId: String): Bundle = Bundle().apply { putString(ARG_CONTACT_ID, contactId) }

        fun newInstance(contactId: String): GroupInCommonFragment = GroupInCommonFragment().apply { arguments = arguments(contactId) }
    }
}

/** Groups in common with a contact as a detail destination (home stack, or its shell Activity). */
class GroupInCommonDestination(private val contactId: String) : DetailDestination {
    override fun newFragment(): Fragment = GroupInCommonFragment.newInstance(contactId)
    override fun shellIntent(context: Context): Intent = GroupInCommonActivity.intent(context, contactId)
    override val subjectId: String get() = contactId
}
