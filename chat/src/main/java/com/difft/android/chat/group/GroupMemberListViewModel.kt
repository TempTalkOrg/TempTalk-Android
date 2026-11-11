package com.difft.android.chat.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.sampleAfterFirst
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.group.GroupMemberListContract.Effect
import com.difft.android.chat.group.GroupMemberListContract.Intent
import com.difft.android.chat.group.GroupMemberListContract.Member
import com.difft.android.chat.group.GroupMemberListContract.State
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.difft.app.database.convertToContactorModels
import org.difft.app.database.groupMemberCounts
import org.difft.app.database.members
import org.difft.app.database.models.DBGroupModel
import org.difft.app.database.wcdb
import javax.inject.Inject


class GroupMemberListSource @Inject constructor(private val groupUtil: GroupUtil) {
    



    fun changes(gid: String) = merge(
        groupUtil.singleGroupsUpdate.filter { it.gid == gid }.map { Unit },
        groupUtil.getGroupsStatusUpdate.filter { it.first }.map { Unit },
        ContactorUtil.contactsUpdate.sampleAfterFirst(CONTACT_CHANGES_SAMPLE_MS).map { Unit },
    )

    
    data class Read(val members: List<Member>, val serverAnswered: Boolean?)

    
    suspend fun members(gid: String, mayFetch: Boolean): Read = withContext(Dispatchers.IO) {
        var group = wcdb.group.getFirstObject(DBGroupModel.gid.eq(gid))
        var serverAnswered: Boolean? = null
        
        
        if (mayFetch && (group == null || (wcdb.groupMemberCounts(listOf(gid))[gid] ?: 0) == 0)) {
            val fetched = groupUtil.fetchAndSaveSingleGroupInfo(gid)
            serverAnswered = fetched != null
            group = fetched ?: group
        }
        group ?: return@withContext Read(emptyList(), serverAnswered)
        Read(group.members.convertToContactorModels().mapNotNull { contact ->
            val id = contact.id ?: return@mapNotNull null
            val member = contact.groupMemberContactor
            
            val searchNames = listOfNotNull(contact.remark, contact.publicName, contact.name, member?.remark, member?.displayName)
                .filter { it.isNotBlank() }.distinct()
            Member(id, contact.getDisplayNameForUI(), contact, member?.groupRole ?: GROUP_ROLE_MEMBER, searchNames)
        }.sortedForMemberList(), serverAnswered)
    }
}

@HiltViewModel
class GroupMemberListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val source: GroupMemberListSource,
) : ViewModel() {
    private val saved = savedStateHandle
    private val _state = MutableStateFlow(State(groupId = savedStateHandle[ARG_GROUP_ID] ?: "",
        query = savedStateHandle[KEY_QUERY] ?: ""))
    val state = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()
    
    private val loads = Mutex()
    




    @Volatile private var serverAnswered = false

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch { source.changes(_state.value.groupId).collect { load() } }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.QueryChanged -> {
                
                saved[KEY_QUERY] = intent.query
                _state.update { it.copy(query = intent.query) }
                
                if (_state.value.members.isEmpty() && !serverAnswered && !loads.isLocked) viewModelScope.launch { load() }
            }
            is Intent.Open -> viewModelScope.launch { _effect.send(Effect.OpenContact(intent.id, _state.value.groupId)) }
        }
    }

    private suspend fun load() = loads.withLock {
        val gid = _state.value.groupId
        try {
            val read = source.members(gid, mayFetch = !serverAnswered)
            if (read.serverAnswered == true) serverAnswered = true
            _state.update { it.copy(members = read.members, loading = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[GroupMemberList] load_failed gid=$gid error=${e.stackTraceToString()}" }
            _state.update { it.copy(loading = false) }
        }
    }

    companion object {
        const val ARG_GROUP_ID = "groupId"
        private const val KEY_QUERY = "query"
    }
}

private const val CONTACT_CHANGES_SAMPLE_MS = 2000L
