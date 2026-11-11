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
import com.difft.android.call.ui.invite.InviteScreenState
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import io.livekit.android.room.participant.RemoteParticipant
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

enum class InviteRequestState {
    SUCCESS,
    FAILED,
}


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

    private val _selectedMembers = MutableStateFlow<List<InviteMember>>(emptyList())
    val selectedMembers: StateFlow<List<InviteMember>> = _selectedMembers.asStateFlow()

    private val _currentState = MutableStateFlow<InviteScreenState>(InviteScreenState.INITIAL)
    val currentState: StateFlow<InviteScreenState> = _currentState.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactorModel>>(emptyList())
    val contacts: StateFlow<List<ContactorModel>> = _contacts.asStateFlow()

    private val _searchKeyword = MutableStateFlow<String>("")
    val searchKeyword: StateFlow<String> = _searchKeyword.asStateFlow()

    private val _filteredContacts = MutableStateFlow<List<ContactorModel>>(emptyList())
    val filteredContacts: StateFlow<List<ContactorModel>> = _filteredContacts.asStateFlow()

    private val _selectedContactIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedContactIds: StateFlow<Set<String>> = _selectedContactIds.asStateFlow()

    private val _inMeetingIds = MutableStateFlow<Set<String>>(emptySet())
    val inMeetingIds: StateFlow<Set<String>> = _inMeetingIds.asStateFlow()

    
    private var excludedIds: Set<String> = emptySet()

    init {
        excludedIds = setOf(globalServices.myId)
    }

    fun setCurrentState(state: InviteScreenState) {
        _currentState.value = state
    }

    fun setSelectedContactIds(ids: Set<String>) {
        _selectedContactIds.value = ids
    }

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
            loadContacts()
            setCurrentState(InviteScreenState.ADD_MEMBERS)
            viewModel.callUiController.setShowInviteViewEnable(true)
        }
    }

    fun setExcludedIds(ids: List<String>) {
        excludedIds = ids.toSet() + globalServices.myId
        _inMeetingIds.value = ids.filter { it != globalServices.myId }.toSet()
    }

    fun loadContacts() {
        scope.launch {
            try {
                val allContacts = withContext(Dispatchers.IO) {
                    wcdb.contactor.allObjects
                        .filter { !callToChatController.isOfficialAccount(it.id) && it.id != globalServices.myId }
                }
                _contacts.value = allContacts
                updateFilteredContacts()
            } catch (e: Exception) {
                L.e { "[Call] InviteCallHandler Failed to load contacts: ${e.message}" }
            }
        }
    }

    fun searchContacts(keyword: String) {
        _searchKeyword.value = keyword
        updateFilteredContacts()
    }

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

    suspend fun addSelectedContacts(contactIds: List<String>) {
        try {
            val contactors = withContext(Dispatchers.IO) {
                wcdb.getContactorsFromAllTable(contactIds)
            }
            val newMembers = contactors.map { contactor ->
                InviteMember(uid = contactor.id, name = contactor.getDisplayNameForUI())
            }
            _selectedMembers.update { existingMembers ->
                (existingMembers + newMembers).distinctBy { it.uid }
            }
        } catch (e: Exception) {
            L.e { "[Call] InviteCallHandler Failed to add selected contacts: ${e.message}" }
        }
    }

    fun removeMember(member: InviteMember) {
        val newMembers = _selectedMembers.value.toMutableList()
        newMembers.remove(member)
        _selectedMembers.value = newMembers
    }

    fun clearSelectedMembers() {
        _selectedMembers.value = emptyList()
    }

    fun clearSearchKeyword() {
        _searchKeyword.value = ""
    }

    fun clearSelectedContactIds() {
        _selectedContactIds.value = emptySet()
    }

    
    fun resetExcludedIds() {
        excludedIds = setOf(globalServices.myId)
        _inMeetingIds.value = emptySet()
    }

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

    private suspend fun buildExcludedUserIds(): List<String> {
        val excludedIds = mutableListOf<String>()

        excludedIds.add(callToChatController.getMySelfUid())

        
        val remoteIdentities = viewModel.participants.value
            .filterIsInstance<RemoteParticipant>()
            .mapNotNull { it.identity?.value }
            .sorted()
        remoteIdentities.forEach { identityValue ->
            val userId = extractUserId(identityValue)
            excludedIds.add(userId)
        }

        return excludedIds
    }

    
    private fun extractUserId(participantValue: String): String {
        return if (participantValue.contains(".")) {
            participantValue.split(".")[0]
        } else {
            participantValue
        }
    }

    
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

    
    private fun getCallTypeForInvite(): String {
        return if (isInstantCallType()) {
            CallType.INSTANT.type
        } else {
            CallType.GROUP.type
        }
    }

    private fun isInstantCallType(): Boolean {
        val currentCallType = viewModel.callType.value
        return currentCallType == CallType.ONE_ON_ONE.type || currentCallType == CallType.INSTANT.type
    }

    suspend fun inviteMembers(): Pair<InviteRequestState, List<String>> {
        val memberIds = selectedMembers.value.map { it.uid }
        if (memberIds.isEmpty()) {
            L.w { "[Call] InviteCallHandler No members to invite" }
            return InviteRequestState.FAILED to memberIds
        }

        val roomId = viewModel.getRoomId() ?: return InviteRequestState.FAILED to memberIds

        val state = callToChatController.inviteCall(
            roomId = roomId,
            roomName = getRoomNameForInvite(),
            callType = getCallTypeForInvite(),
            mKey = viewModel.getE2eeKey(),
            inviteMembers = ArrayList(memberIds),
            conversationId = callIntent.conversationId,
        )
        return state to memberIds
    }
}
