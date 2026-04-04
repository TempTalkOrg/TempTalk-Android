package com.difft.android.chat.recent

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.difft.android.base.call.CallData
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.ui.smoothScrollToPositionWithHelper
import com.difft.android.base.user.LogoutManager
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.TextSizeUtil
import com.difft.android.base.utils.globalServices
import com.difft.android.base.widget.ChativePopupView
import com.difft.android.base.widget.ChativePopupWindow
import com.difft.android.base.widget.ComposeDialog
import com.difft.android.base.widget.ComposeDialogManager
import com.difft.android.base.widget.ToastUtil
import com.difft.android.call.manager.CallDataManager
import com.difft.android.chat.R
import com.difft.android.chat.databinding.ChatFragmentRecentChatBinding
import com.difft.android.chat.group.CreateGroupActivity
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupChatContentActivity
import com.difft.android.chat.invite.InviteCodeActivity
import com.difft.android.chat.invite.InviteUtils
import com.difft.android.chat.invite.ScanActivity
import com.difft.android.chat.recent.RoomViewData.Type
import com.difft.android.chat.ui.ChatActivity
import com.difft.android.messageserialization.db.store.ConversationUtils
import com.difft.android.messageserialization.db.store.DBRoomStore
import com.difft.android.network.config.GlobalConfigsManager
import com.difft.android.network.group.AddOrRemoveMembersReq
import com.difft.android.network.group.GroupRepo
import com.difft.android.websocket.api.push.exceptions.AuthorizationFailedException
import com.difft.android.websocket.api.websocket.WebSocketConnectionState
import dagger.hilt.android.AndroidEntryPoint
import difft.android.messageserialization.model.MENTIONS_TYPE_NONE
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

import kotlinx.coroutines.withContext
import org.difft.app.database.members
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.models.GroupModel
import org.difft.app.database.wcdb
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies
import org.thoughtcrime.securesms.util.MessageNotificationUtil
import org.thoughtcrime.securesms.websocket.WebSocketManager
import javax.inject.Inject
import dagger.Lazy
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@ExperimentalCoroutinesApi
@AndroidEntryPoint
class RecentChatFragment : Fragment(), DualPaneSelectionListener {

    @Inject
    lateinit var userManager: UserManager

    @Inject
    lateinit var groupRepo: GroupRepo

    @Inject
    lateinit var globalConfigsManager: GlobalConfigsManager

    @Inject
    lateinit var inviteUtils: InviteUtils

    @Inject
    lateinit var messageNotificationUtil: MessageNotificationUtil

    @Inject
    lateinit var webSocketManager: WebSocketManager

    @Inject
    lateinit var dbRoomStore: DBRoomStore

    @Inject
    lateinit var logoutManager: LogoutManager

    @Inject
    lateinit var callDataManagerLazy: Lazy<CallDataManager>

    private lateinit var binding: ChatFragmentRecentChatBinding
    private var popupWindow: PopupWindow? = null
    private lateinit var popupItemList: MutableList<ChativePopupView.Item>
    private val recentChatViewModel: RecentChatViewModel by viewModels(ownerProducer = { requireActivity() })

    private val callDataManager: CallDataManager by lazy {
        callDataManagerLazy.get()
    }

    private val mAdapter: RecentChatAdapter by lazy {
        object : RecentChatAdapter(requireActivity()) {
            override fun onItemClicked(
                roomViewData: RoomViewData,
                position: Int
            ) {
                // Skip processing for instant calls as they don't require chat room navigation
                if (roomViewData.isInstantCall) {
                    return
                }

                // Check if parent Activity supports dual-pane navigation
                val navigationCallback = activity as? ConversationNavigationCallback

                when (roomViewData.type) {
                    is Type.OneOnOne -> {
                        L.i { "onItemClicked - OneOnOne ChattingRoom" }
                        if (navigationCallback != null) {
                            navigationCallback.onOneOnOneConversationSelected(roomViewData.roomId)
                            if (navigationCallback.isDualPaneMode) {
                                selectedId = roomViewData.roomId
                            }
                        } else {
                            ChatActivity.startActivity(
                                requireActivity(),
                                roomViewData.roomId
                            )
                        }
                    }

                    is Type.Group -> {
                        L.i { "onItemClicked - Group ChattingRoom" }
                        if (navigationCallback != null) {
                            navigationCallback.onGroupConversationSelected(roomViewData.roomId)
                            if (navigationCallback.isDualPaneMode) {
                                selectedId = roomViewData.roomId
                            }
                        } else {
                            GroupChatContentActivity.startActivity(
                                requireActivity(),
                                roomViewData.roomId
                            )
                        }
                    }
                }
            }


            override fun onItemLongClicked(view: View, roomViewData: RoomViewData, position: Int, touchX: Int, touchY: Int) {
                showItemActionsPop(view, roomViewData, touchX, touchY)
            }

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerCallingListener()
    }

    private fun registerCallingListener() {
        callDataManager.callingList
            .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChanged()
            .onEach { callingList ->
                L.d { "[Call] RecentChatFragment registerCallingListener: callingList = $callingList" }
                val currentList: List<RoomViewData> = recentChatViewModel.allRecentRoomsStateFlow.value
                sortingChatRoomsByCall(currentList, callingList)
            }
            .launchIn(lifecycleScope)
    }

    private fun buildSortedListForCalling(
        currentList: List<RoomViewData>,
        callingList: Map<String, CallData>?,
        instantCallRoomViewData: Map<String, RoomViewData>,
        instantCallDefaultName: String
    ): Pair<List<RoomViewData>, Map<String, RoomViewData>> {
        L.d { "[Call] RecentChatFragment moveCallingRoomsToTheTop: callingList = $callingList" }
        val workingList = currentList.map { it.copy() }.toMutableList()
        val instantCallRoomViewDataMap = instantCallRoomViewData.toMutableMap()
        // 如果没有callingList，则清除currentList中的callData 并清除instantCallRoomViewData的记录
        if (callingList.isNullOrEmpty()) {
            workingList.forEach { item ->
                item.callData = null
            }
            instantCallRoomViewDataMap.clear()
        }

        // 针对instant call 创建roomViewData
        val currentRoomIds: List<String> = workingList.map { it.roomId }.toList()
        callingList?.forEach { callDataMap ->
            val callData = callDataMap.value
            val roomId = callData.roomId
            when (callData.type) {
                CallType.INSTANT.type -> {
                    if (!currentRoomIds.contains(roomId) && !instantCallRoomViewDataMap.containsKey(roomId)) {
                        val callName = callData.callName ?: instantCallDefaultName
                        val roomViewData = RoomViewData(
                            roomId = roomId,
                            type = Type.Group,
                            roomName = callName,
                            lastActiveTime = callData.createdAt,
                            unreadMessageNum = 0,
                            pinnedTime = null,
                            muteStatus = 0,
                            mentionType = MENTIONS_TYPE_NONE,
                            isInstantCall = true,
                            callData = callData
                        )
                        instantCallRoomViewDataMap[roomId] = roomViewData
                    }
                }
            }
        }

        workingList.forEach { item ->
            val conversationId = item.roomId
            val callData = item.callData
            if (callData != null) {
                val roomId = item.callData?.roomId
                //取消join按钮的判断 roomId不在callingList
                if (!roomId.isNullOrEmpty()) {
                    if (callingList?.containsKey(roomId) != true) {
                        item.callData = null
                        instantCallRoomViewDataMap.remove(roomId)
                    } else if ((callData.type == CallType.INSTANT.type && !callingList.containsKey(item.roomId))) {
                        item.callData = null
                    }
                }
            } else {
                // callingList not empty，则判断是否在callingList中，如果在，则设置isJoinEnable = true
                callingList?.mapNotNull { callDataMap ->
                    val conversation = callDataMap.value.conversation
                    val type = callDataMap.value.type
                    if (!type.isNullOrEmpty()) {
                        when (type) {
                            CallType.GROUP.type -> {
                                if (!conversation.isNullOrEmpty() && conversationId.isNotEmpty() && conversationId == conversation) {
                                    item.callData = callDataMap.value
                                    item.lastActiveTime = callDataMap.value.createdAt
                                }
                            }

                            CallType.ONE_ON_ONE.type -> {
                                val id = callDataMap.value.caller.uid
                                if (!id.isNullOrEmpty() && id != globalServices.myId && conversationId.isNotEmpty() && id == conversationId) {
                                    item.callData = callDataMap.value
                                    item.lastActiveTime = callDataMap.value.createdAt
                                }
                            }
                        }
                    }
                }
            }
        }

        val sortedList = ArrayList<RoomViewData>()

        // filter the pinned rooms
        val pinnedRooms = workingList.filter { item ->
            item.isPinned && item.callData == null
        }
        pinnedRooms.sortedByDescending { item ->
            item.pinnedTime
        }
        // filter the meeting rooms
        val meetingRooms = workingList.filter { item ->
            item.callData != null && item.callData?.type != CallType.INSTANT.type
        }
        // filter the others rooms
        val otherRooms = workingList.filter { item ->
            item.callData == null && meetingRooms.contains(item).not() && pinnedRooms.contains(item).not()
        }

        // add instant call rooms
        val currentInstantCallRooms = workingList.filter { item ->
            item.callData != null && item.callData?.type == CallType.INSTANT.type
        }
        if (instantCallRoomViewDataMap.isNotEmpty()) {
            val existingRoomIds = currentInstantCallRooms.map { it.roomId }.toSet()
            val newInstantRooms = instantCallRoomViewDataMap.values.filterNot { it.roomId in existingRoomIds }
            val roomsToAdd = if (newInstantRooms.isNotEmpty()) {
                currentInstantCallRooms + newInstantRooms
            } else {
                currentInstantCallRooms
            }
            sortedList.addAll(roomsToAdd.sortedByDescending { it.lastActiveTime })

        } else {
            sortedList.addAll(currentInstantCallRooms.sortedByDescending { it.lastActiveTime })
        }

        // add meeting rooms
        sortedList.addAll(meetingRooms.sortedByDescending { it.lastActiveTime })
        // add pinned rooms
        sortedList.addAll(pinnedRooms.sortedByDescending { it.lastActiveTime })
        // add other rooms (sorted by lastActiveTime descending)
        sortedList.addAll(otherRooms.sortedByDescending { it.lastActiveTime })
        return sortedList to instantCallRoomViewDataMap
    }

    private fun submitSortedChatRooms(sortedList: List<RoomViewData>) {
        val items = mutableListOf<ListItem>()
        items.add(ListItem.SearchInput)
        items.addAll(sortedList.map { ListItem.ChatItem(it) })
        mAdapter.submitList(items)
    }

    private suspend fun sortingChatRoomsByCall(currentList: List<RoomViewData>, callingList: Map<String, CallData>? = null) {
        val instantSnapshot = recentChatViewModel.instantCallRoomViewData.toMap()
        val instantCallDefaultName = getString(com.difft.android.call.R.string.call_instant_call_title_default)
        val (sortedList, updatedInstantMap) = withContext(Dispatchers.Default) {
            buildSortedListForCalling(currentList, callingList, instantSnapshot, instantCallDefaultName)
        }
        if (!isAdded || view == null || !this::binding.isInitialized) return
        recentChatViewModel.instantCallRoomViewData.clear()
        recentChatViewModel.instantCallRoomViewData.putAll(updatedInstantMap)
        submitSortedChatRooms(sortedList)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val binding = ChatFragmentRecentChatBinding.inflate(inflater, container, false)
        this.binding = binding
        return binding.root
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initActions()

        binding.recyclerViewRecentMessage.apply {
            this.adapter = mAdapter
            layoutManager = LinearLayoutManager(requireContext())
            itemAnimator = null
        }

        recentChatViewModel.retrieveCallingList()

        observeChatWSConnection()

        recentChatViewModel.allRecentRoomsStateFlow
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { list ->
                L.i { "[ChatList] Conversation list refresh ====== -> size:" + list.size }
                sortingChatRooms(list)
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        recentChatViewModel.createNote()

        RecentChatUtil.chatDoubleTab
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach {
                val layoutManager = binding.recyclerViewRecentMessage.layoutManager as? LinearLayoutManager ?: return@onEach
                val firstVisible = layoutManager.findFirstVisibleItemPosition()

                val firstUnreadItemPosition = mAdapter.currentList.indexOfFirst { data ->
                    data.chatItemData()?.isMuted == false && (data.chatItemData()?.unreadMessageNum ?: 0) > 0
                }

                val canScrollDown = binding.recyclerViewRecentMessage.canScrollVertically(1)

                var nextUnreadItemPosition = if (!canScrollDown) {
                    firstUnreadItemPosition
                } else {
                    mAdapter.currentList
                        .drop(firstVisible + 1)
                        .indexOfFirst { data ->
                            data.chatItemData()?.isMuted == false && (data.chatItemData()?.unreadMessageNum ?: 0) > 0
                        }
                        .let { if (it != -1) it + firstVisible + 1 else firstUnreadItemPosition }
                }
                if (nextUnreadItemPosition == -1) {
                    nextUnreadItemPosition = 0
                }
                binding.recyclerViewRecentMessage.post {
                    if (canScrollDown) {
                        binding.recyclerViewRecentMessage.smoothScrollToPositionWithHelper(requireContext(), nextUnreadItemPosition, 100f)
                    } else {
                        binding.recyclerViewRecentMessage.scrollToPosition(nextUnreadItemPosition)
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        TextSizeUtil.textSizeState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .onEach { mAdapter.notifyDataSetChanged() }
            .launchIn(viewLifecycleOwner.lifecycleScope)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    mAdapter.updateCallBarTick()
                    delay(1000)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        ConversationUtils.isConversationListVisible = true

        recentChatViewModel.retrieveCallingList()

        // Restore selected state for dual-pane mode
        val navigationCallback = activity as? ConversationNavigationCallback
        if (navigationCallback?.isDualPaneMode == true) {
            mAdapter.selectedId = navigationCallback.currentSelectedConversationId
        }
    }

    override fun updateDualPaneSelection(selectedId: String?) {
        mAdapter.selectedId = selectedId
    }

    override fun onPause() {
        super.onPause()

        ConversationUtils.isConversationListVisible = false
    }

    private fun initActions() {
        popupItemList = mutableListOf<ChativePopupView.Item>().apply {
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_create_group),
                    label = getString(R.string.chat_option_create_group)
                ) {
                    startActivity(Intent(activity, CreateGroupActivity::class.java))
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_add_contact),
                    label = getString(R.string.chat_option_add_contact)
                ) {
                    InviteCodeActivity.startActivity(requireActivity())
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_my_code),
                    label = getString(R.string.invite_my_code)
                ) {
                    inviteUtils.showInviteDialog(requireActivity())
                    popupWindow?.dismiss()
                })
            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_scan),
                    label = getString(R.string.chat_option_scan)
                ) {
                    ScanActivity.startActivity(requireActivity())
                    popupWindow?.dismiss()
                })

            add(
                ChativePopupView.Item(
                    drawable = ResUtils.getDrawable(R.drawable.chat_tabler_mark_all_as_read),
                    label = getString(R.string.chat_option_mark_all_as_read)
                ) {
                    recentChatViewModel.markAllAsRead(requireActivity())
                    popupWindow?.dismiss()
                })
        }
        binding.addOptions.setOnClickListener {
            popupWindow = ChativePopupWindow.showAsDropDown2(
                binding.addOptions,
                popupItemList
            )
        }
    }

    private fun checkDevices() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApplicationDependencies.getSignalServiceAccountManager().devices
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[RecentChatFragment] checkDevices error: ${e.stackTraceToString()}" }
                if (e is AuthorizationFailedException) {
                    L.i { "[Message] AuthorizationFailedException" }
                    logoutManager.doLogoutWithoutRemoveData()
                }
            }
        }
    }

    private var inactiveDeviceDialog: ComposeDialog? = null

    //显示设备非活跃激活提示对话框
    private fun showInactiveDeviceDialog() {
        inactiveDeviceDialog?.dismiss()
        inactiveDeviceDialog = null

        inactiveDeviceDialog = ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.activate_device_title),
            message = getString(R.string.activate_device_tips),
            confirmText = getString(R.string.activate_device),
            cancelable = false,
            showCancel = false,
            onConfirm = {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            recentChatViewModel.activateDevice()
                        }
                        if (!isAdded || view == null) return@launch
                        if (result.status == 0) {
                            ToastUtil.show(R.string.activate_device_success)
                            inactiveDeviceDialog?.dismiss()
                            inactiveDeviceDialog = null
                        } else {
                            result.reason?.let { message -> ToastUtil.show(message) }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (!isAdded || view == null) return@launch
                        L.e { "activate Device fail:" + e.stackTraceToString() }
                        e.message?.let { message -> ToastUtil.show(message) }
                    }
                }
            }
        )
    }


    private suspend fun sortingChatRooms(
        list: List<RoomViewData>
    ) {
        val callingList = callDataManager.getCallListData()
        val instantSnapshot = recentChatViewModel.instantCallRoomViewData.toMap()
        val instantCallDefaultName = getString(com.difft.android.call.R.string.call_instant_call_title_default)
        val (sortedList, updatedInstantMap) = withContext(Dispatchers.Default) {
            buildSortedListForCalling(list, callingList, instantSnapshot, instantCallDefaultName)
        }
        if (!isAdded || view == null || !this::binding.isInitialized) return
        recentChatViewModel.instantCallRoomViewData.clear()
        recentChatViewModel.instantCallRoomViewData.putAll(updatedInstantMap)
        submitSortedChatRooms(sortedList)
    }

    private fun observeChatWSConnection() {
        webSocketManager.getWebSocketConnection()
            .webSocketConnectionState
            .flowWithLifecycle(viewLifecycleOwner.lifecycle, Lifecycle.State.STARTED)
            .distinctUntilChanged()
            .onEach { state ->
                L.i { "[ws][RecentChatFragment] chat webSocketConnectionState changed:$state" }
                when (state) {
                    WebSocketConnectionState.DISCONNECTED,
                    WebSocketConnectionState.FAILED,
                    WebSocketConnectionState.AUTHENTICATION_FAILED,
                    WebSocketConnectionState.UNKNOWN_HOST_FAILED,
                        -> {
                        binding.chatTitle.text = getString(R.string.chat_state_offline)
                        checkDevices()
                    }

                    WebSocketConnectionState.INACTIVE_FAILED -> {
                        binding.chatTitle.text = getString(R.string.chat_state_offline)
                        showInactiveDeviceDialog()
                    }

                    WebSocketConnectionState.CONNECTING,
                        -> {
                        binding.chatTitle.text = getString(R.string.chat_state_connecting)
                    }

                    WebSocketConnectionState.CONNECTED -> {
                        binding.chatTitle.text = getString(R.string.chat_chats)
                    }

                    else -> {
                        binding.chatTitle.text = getString(R.string.chat_chats)
                    }
                }
            }
            .launchIn(viewLifecycleOwner.lifecycleScope)
    }

    private fun leaveGroup(group: GroupModel) {
        ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.group_leave),
            message = getString(R.string.group_leave_notice),
            confirmText = getString(R.string.group_leave_leave),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = {
                viewLifecycleOwner.lifecycleScope.launch {
                    ComposeDialogManager.showWait(requireActivity())
                    try {
                        val response = withContext(Dispatchers.IO) {
                            groupRepo.leaveGroup(group.gid, AddOrRemoveMembersReq(mutableListOf(globalServices.myId)))
                        }
                        if (!response.isSuccess()) {
                            ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[RecentChatFragment] leaveGroup error: ${e.stackTraceToString()}" }
                        ToastUtil.show(R.string.chat_net_error)
                    } finally {
                        ComposeDialogManager.dismissWait()
                    }
                }
            }
        )
    }

    private fun disbandGroup(group: GroupModel) {
        ComposeDialogManager.showMessageDialog(
            context = requireActivity(),
            title = getString(R.string.group_disband),
            message = getString(R.string.group_disband_tips),
            confirmText = getString(R.string.group_disband_disband),
            cancelText = getString(R.string.group_leave_cancel),
            cancelable = false,
            onConfirm = {
                viewLifecycleOwner.lifecycleScope.launch {
                    ComposeDialogManager.showWait(requireActivity())
                    try {
                        val response = withContext(Dispatchers.IO) {
                            groupRepo.deleteGroup(group.gid)
                        }
                        if (!response.isSuccess()) {
                            ToastUtil.show(response.reason ?: getString(R.string.operation_failed))
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w { "[RecentChatFragment] disbandGroup error: ${e.stackTraceToString()}" }
                        ToastUtil.show(R.string.chat_net_error)
                    } finally {
                        ComposeDialogManager.dismissWait()
                    }
                }
            }
        )
    }

    private fun showItemActionsPop(rootView: View, data: RoomViewData, touchX: Int, touchY: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
        val groupData = if (data.type is Type.Group) {
            withContext(Dispatchers.IO) {
                val group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(data.roomId))
                if (group != null && group.status == 0) {
                    val role = group.members.find { it.id == globalServices.myId }?.groupRole
                    if (role != null) Pair(group, role) else null
                } else null
            }
        } else null

        val itemList = mutableListOf<ChativePopupView.Item>().apply {

            if (data.isPinned) {
                add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_unstick), requireActivity().getString(R.string.chat_unstick)) {
                    recentChatViewModel.pinChattingRoom(false, data)
                    popupWindow?.dismiss()
                })
            } else {
                add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_stick), requireActivity().getString(R.string.chat_stick)) {
                    recentChatViewModel.pinChattingRoom(true, data)
                    popupWindow?.dismiss()
                })
            }


            val channelId: String = data.roomId

            if (data.isMuted) {
                add(
                    ChativePopupView.Item(
                        ResUtils.getDrawable(R.drawable.chat_icon_unmute),
                        requireActivity().getString(R.string.chat_action_unmute)
                    ) {
                        recentChatViewModel.muteChannel(
                            context = requireContext(),
                            channelId = channelId,
                            muteStatus = 0
                        )
                        popupWindow?.dismiss()
                    })
            } else {
                add(
                    ChativePopupView.Item(
                        ResUtils.getDrawable(R.drawable.chat_icon_mute),
                        requireActivity().getString(R.string.chat_action_mute)
                    ) {
                        recentChatViewModel.muteChannel(
                            context = requireContext(),
                            channelId = channelId!!,
                            muteStatus = 1
                        )
                        popupWindow?.dismiss()
                    })
            }

            if (groupData != null) {
                val (group, role) = groupData
                    if (role == GROUP_ROLE_OWNER) {
                        add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_disband_new), requireActivity().getString(R.string.group_disband), ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.error)) {
                            disbandGroup(group)
                            popupWindow?.dismiss()
                        })
                    } else {
                        add(ChativePopupView.Item(ResUtils.getDrawable(R.drawable.chat_icon_group_leave), requireActivity().getString(R.string.group_leave), ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.error)) {
                            leaveGroup(group)
                            popupWindow?.dismiss()
                        })
                    }
            }
            add(
                ChativePopupView.Item(
                    ResUtils.getDrawable(R.drawable.chat_message_action_delete),
                    requireActivity().getString(R.string.me_delete),
                    ContextCompat.getColor(requireContext(), com.difft.android.base.R.color.error)
                ) {
                    ApplicationDependencies.getMessageStore()
                        .removeRoomAndMessages(data.roomId)
                    popupWindow?.dismiss()
                })
        }
        popupWindow = ChativePopupWindow.showAtTouchPosition(rootView, itemList, touchX, touchY)
        }
    }


}