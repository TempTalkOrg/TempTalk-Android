package com.difft.android.call.handler

import android.app.Activity
import android.content.Context
import com.difft.android.base.call.CallType
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.ApplicationHelper
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.call.CallIntent
import com.difft.android.call.LCallToChatController
import com.difft.android.call.LCallViewModel
import com.difft.android.call.R
import com.difft.android.call.data.InviteMember
import com.difft.android.call.manager.ContactorCacheManager
import com.difft.android.call.ui.InviteScreenState
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.getContactorsFromAllTable
import java.util.ArrayList

@dagger.hilt.EntryPoint
@InstallIn(SingletonComponent::class)
interface InviteCallHandlerEntryPoint {
    val wcdb: WCDB
}

/**
 * 会议邀请请求的状态枚举
 */
enum class InviteRequestState {
    SUCCESS,      // 邀请成功
    FAILED,  // 邀请失败
}

/**
 * 统一管理邀请用户加入通话的逻辑
 * 负责构建排除列表、获取房间名称、处理邀请流程
 * 同时管理邀请页面的状态（联系人列表、搜索、已选成员等）
 */
class InviteCallHandler(
    private val viewModel: LCallViewModel,
    private val callToChatController: LCallToChatController,
    private val contactorCacheManager: ContactorCacheManager,
    private val callIntent: CallIntent,
    private val scope: CoroutineScope
) {
    private val mySelfId: String by lazy {
        globalServices.myId
    }

    private val wcdb: WCDB by lazy {
        EntryPointAccessors.fromApplication<InviteCallHandlerEntryPoint>(
            ApplicationHelper.instance
        ).wcdb
    }

    // 已选择的成员列表
    private val _selectedMembers = MutableStateFlow<List<InviteMember>>(emptyList())
    val selectedMembers: StateFlow<List<InviteMember>> = _selectedMembers.asStateFlow()

    private val _currentState = MutableStateFlow<InviteScreenState>(InviteScreenState.INITIAL)
    val currentState: StateFlow<InviteScreenState> = _currentState.asStateFlow()

    // 联系人列表
    private val _contacts = MutableStateFlow<List<ContactorModel>>(emptyList())
    val contacts: StateFlow<List<ContactorModel>> = _contacts.asStateFlow()

    // 搜索关键词
    private val _searchKeyword = MutableStateFlow<String>("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    // 过滤后的联系人列表（根据搜索关键词）
    private val _filteredContacts = MutableStateFlow<List<ContactorModel>>(emptyList())
    val filteredContacts: StateFlow<List<ContactorModel>> = _filteredContacts.asStateFlow()

    private val _selectedContactIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedContactIds: StateFlow<Set<String>> = _selectedContactIds.asStateFlow()

    private val _inMeetingIds = MutableStateFlow<Set<String>>(emptySet())
    val inMeetingIds: StateFlow<Set<String>> = _inMeetingIds.asStateFlow()

    // 需要排除的用户ID列表（已在会议中的用户和自己）
    private var excludedIds: Set<String> = emptySet()

    init {
        // 初始化时设置排除列表包含自己
        excludedIds = setOf(globalServices.myId)
    }

    fun setCurrentState(state: InviteScreenState) {
        _currentState.value = state
    }

    fun setSelectedContactIds(ids: Set<String>) {
        _selectedContactIds.value = ids
    }

    /**
     * 邀请用户加入通话
     *
     * @param context Activity context，用于启动邀请界面和获取字符串资源
     */
    fun inviteUsers(context: Context) {
        L.d { "[Call] InviteCallHandler inviteUsers" }
        
        if (context !is Activity) {
            L.e { "[Call] InviteCallHandler context is not Activity" }
            return
        }

        scope.launch {
            val excludedIdsList = buildExcludedUserIds()
            L.d { "[Call] InviteCallHandler excludedIdsList: $excludedIdsList" }
            setExcludedIds(excludedIdsList)
            // 初始化联系人列表
            loadContacts()
            // 打开邀请页时默认展示联系人列表页
            setCurrentState(InviteScreenState.ADD_MEMBERS)
            // 显示邀请页面
            viewModel.callUiController.setShowInviteViewEnable(true)
        }
    }

    /**
     * 设置需要排除的用户ID列表
     */
    fun setExcludedIds(ids: List<String>) {
        excludedIds = ids.toSet() + globalServices.myId
        _inMeetingIds.value = ids.filter { it != globalServices.myId }.toSet()
    }

    /**
     * 加载联系人列表
     */
    fun loadContacts() {
        scope.launch {
            try {
                val allContacts = withContext(Dispatchers.IO) {
                    wcdb.contactor.allObjects
                        .filter { !callToChatController.isBotId(it.id) && it.id != globalServices.myId }
                }
                _contacts.value = allContacts
                updateFilteredContacts()
            } catch (e: Exception) {
                L.e { "[Call] InviteCallHandler Failed to load contacts: ${e.message}" }
            }
        }
    }

    /**
     * 搜索联系人
     */
    fun searchContacts(keyword: String) {
        _searchKeyword.value = keyword
        updateFilteredContacts()
    }

    /**
     * 更新过滤后的联系人列表
     */
    fun updateFilteredContacts() {
        scope.launch {
            val keyword = _searchKeyword.value
            val allContacts = _contacts.value

            val filtered = if (keyword.isBlank()) {
                allContacts
            } else {
                withContext(Dispatchers.Default) {
                    val upperKeyword = keyword.uppercase()
                    allContacts.filter { contactor ->
                        contactor.getDisplayNameForUI().uppercase().contains(upperKeyword) ||
                        contactor.id.uppercase().contains(upperKeyword)
                    }
                }
            }
            val selectedUids = _selectedMembers.value.map { it.uid }.toSet()
            _filteredContacts.value = filtered.filter { it.id != globalServices.myId && it.id !in selectedUids }
        }
    }

    /**
     * 获取名称的首字母
     */
    private fun getFirstLetter(name: String?): String {
        if (name.isNullOrBlank()) return "#"
        val firstChar = name.firstOrNull()?.uppercaseChar() ?: return "#"
        return if (firstChar.isLetter() || firstChar in '\u4e00'..'\u9fa5') {
            firstChar.toString()
        } else {
            "#"
        }
    }

    /**
     * 从avatar JSON字符串中提取URL和key
     */
    private fun parseAvatarData(avatarJson: String?): Pair<String?, String?> {
        if (avatarJson.isNullOrBlank()) return null to null
        return try {
            // 简单的JSON解析，提取attachmentId
            val attachmentIdMatch = Regex("\"attachmentId\"\\s*:\\s*\"([^\"]+)\"").find(avatarJson)
            val attachmentId = attachmentIdMatch?.groupValues?.get(1)
            val encKeyMatch = Regex("\"encKey\"\\s*:\\s*\"([^\"]+)\"").find(avatarJson)
            val encKey = encKeyMatch?.groupValues?.get(1)
            attachmentId to encKey
        } catch (e: Exception) {
            L.e { "[Call] InviteCallHandler Failed to parse avatar data: ${e.message}" }
            null to null
        }
    }

    /**
     * 添加选中的联系人到成员列表
     */
    suspend fun addSelectedContacts(contactIds: List<String>) {
        try {
            val contactors = withContext(Dispatchers.IO) {
                wcdb.getContactorsFromAllTable(contactIds)
            }
            val newMembers = contactors.map { contactor ->
                val displayName = contactor.getDisplayNameForUI()
                val (avatarUrl, avatarEncKey) = parseAvatarData(contactor.avatar)
                InviteMember(
                    uid = contactor.id,
                    name = displayName,
                    avatarUrl = avatarUrl,
                    avatarEncKey = avatarEncKey,
                    sortLetter = getFirstLetter(displayName)
                )
            }
            _selectedMembers.update { existingMembers ->
                (existingMembers + newMembers).distinctBy { it.uid }
            }
        } catch (e: Exception) {
            L.e { "[Call] InviteCallHandler Failed to add selected contacts: ${e.message}" }
        }
    }

    /**
     * 移除成员
     */
    fun removeMember(member: InviteMember) {
        val newMembers = _selectedMembers.value.toMutableList()
        newMembers.remove(member)
        _selectedMembers.value = newMembers
    }

    /**
     * 清空已选择的成员
     */
    fun clearSelectedMembers() {
        _selectedMembers.value = emptyList()
    }

    /**
     * 清空搜索关键词
     */
    fun clearSearchKeyword() {
        _searchKeyword.value = ""
    }

    /**
     * 清空已选择的联系人 ID 列表
     */
    fun clearSelectedContactIds() {
        _selectedContactIds.value = emptySet()
    }

    /**
     * 重置排除的 ID 列表，仅包含自己的 ID 和已经在会议中的参与者 ID
     */
    fun resetExcludedIds() {
        excludedIds = setOf(globalServices.myId)
        _inMeetingIds.value = emptySet()
    }

    /**
     * 重置当前状态为初始状态
     */
    fun resetCurrentState() {
        _currentState.value = InviteScreenState.INITIAL
    }

    fun resetState() {
        clearSelectedMembers()
        clearSearchKeyword()
        resetCurrentState()
        clearSelectedContactIds()
        resetExcludedIds()
    }

    /**
     * 构建需要排除的用户 ID 列表
     * 包括：自己、已经在会议中的参与者
     */
    private suspend fun buildExcludedUserIds(): List<String> {
        val excludedIds = mutableListOf<String>()

        // 排除自己
        excludedIds.add(callToChatController.getMySelfUid())

        // 排除已经在会议中的参与者
        val remoteParticipants = viewModel.room.remoteParticipants.keys.sortedBy { it.value }
        remoteParticipants.forEach { remoteParticipant ->
            val userId = extractUserId(remoteParticipant.value)
            excludedIds.add(userId)
        }

        return excludedIds
    }

    /**
     * 从参与者值中提取用户 ID
     * 处理包含 "." 的情况（如 "userId.deviceId"）
     */
    private fun extractUserId(participantValue: String): String {
        return if (participantValue.contains(".")) {
            participantValue.split(".")[0]
        } else {
            participantValue
        }
    }

    /**
     * 获取邀请用户时的房间名称
     * 对于 1v1 或即时通话，使用自己的名称 + 标题后缀
     * 对于群组通话，使用原始房间名称
     */
    private suspend fun getRoomNameForInvite(): String {
        return if (isInstantCallType()) {
            val mySelfName = withContext(Dispatchers.Default) {
                contactorCacheManager.getDisplayNameById(mySelfId) ?: mySelfId
            }
            val instantCallTitle = ResUtils.getString(R.string.call_instant_call_title)
            "$mySelfName$instantCallTitle"
        } else {
            callIntent.roomName
        }
    }

    /**
     * 获取邀请用户时的通话类型
     * 对于 1v1 或即时通话，返回 INSTANT 类型
     * 否则返回 GROUP 类型
     */
    private fun getCallTypeForInvite(): String {
        return if (isInstantCallType()) {
            CallType.INSTANT.type
        } else {
            CallType.GROUP.type
        }
    }

    /**
     * 判断是否为即时通话类型（1v1 或 INSTANT）
     */
    private fun isInstantCallType(): Boolean {
        val currentCallType = viewModel.callType.value
        return currentCallType == CallType.ONE_ON_ONE.type || currentCallType == CallType.INSTANT.type
    }

    /**
     * 发起邀请
     */
    suspend fun inviteMembers(callback: (InviteRequestState, List<String>) -> Unit) {
        val memberIds = selectedMembers.value.map { it.uid }
        if (memberIds.isEmpty()) {
            L.w { "[Call] InviteCallHandler No members to invite" }
            callback.invoke(InviteRequestState.FAILED, memberIds)
            return
        }

        viewModel.getRoomId()?.let { roomId ->
            callToChatController.inviteCall(
                roomId = roomId,
                roomName = getRoomNameForInvite(),
                callType = getCallTypeForInvite(),
                mKey = viewModel.getE2eeKey(),
                inviteMembers = ArrayList(memberIds),
                conversationId = callIntent.conversationId,
                callback = { it ->
                    callback.invoke(it, memberIds)
                }
            )
        }
    }
}