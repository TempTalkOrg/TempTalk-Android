package com.difft.android.chat.group.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.chat.group.GroupWriteSession
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.contactsall.sortedByRoleThenPinyin
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.group.GROUP_ROLE_ADMIN
import com.difft.android.chat.group.GROUP_ROLE_MEMBER
import com.difft.android.chat.group.GROUP_ROLE_OWNER
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.chat.R
import com.difft.android.chat.group.GroupUtil
import com.difft.android.chat.group.mvi.GroupPageContract.Effect
import com.difft.android.chat.group.mvi.GroupPageContract.Intent
import com.difft.android.chat.group.mvi.GroupPageContract.MemberAction
import com.difft.android.chat.group.mvi.GroupPageContract.ModeratorAction
import com.difft.android.chat.group.mvi.GroupPageContract.Page
import com.difft.android.chat.group.mvi.GroupPageContract.Setting
import com.difft.android.chat.group.mvi.GroupPageContract.State
import com.difft.android.network.group.ChangeGroupSettingsReq
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

@HiltViewModel
class GroupPageViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val groupUtil: GroupUtil,
    private val mutations: GroupMutationRepository,
    private val store: GroupPageStore,
    private val credentials: CredentialChangeSignal,
) : ViewModel() {
    val gid: String = savedState[ARG_GID] ?: ""
    val page: Page = enumValueOf(savedState[ARG_PAGE] ?: Page.MANAGEMENT.name)
    val moderatorAction: ModeratorAction = enumValueOf(savedState[ARG_ACTION] ?: ModeratorAction.ADD.name)
    val memberAction: MemberAction = enumValueOf(savedState[ARG_TYPE] ?: MemberAction.ADD.name)
    private val accountId = globalServices.myId
    private val creatorLogin = credentials.credentialHash.value
    private fun isCurrentSession() = accountId == globalServices.myId && creatorLogin != null &&
        credentials.credentialHash.value == creatorLogin
    private val _state = MutableStateFlow(State(
        selectedIds = savedState.get<ArrayList<String>>(KEY_SELECTED)?.toSet().orEmpty(),
        query = savedState[KEY_QUERY] ?: "",
    ))
    val state = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        load()
        viewModelScope.launch {
            groupUtil.singleGroupsUpdate.collect { group ->
                if (group.gid == gid) {
                    if (group.status != 0) {
                        _state.update { it.copy(allowed = false, closeSubject = true) }
                        _effect.send(Effect.CloseSubject)
                    } else load(refresh = false)
                }
            }
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Load -> load()
            is Intent.Search -> {
                savedState[KEY_QUERY] = intent.query
                _state.update { renderRows(it.copy(query = intent.query)) }
            }
            is Intent.Select -> {
                _state.update { old ->
                    val selected = selectGroupMember(old.selectedIds, old.eligibleIds, intent.uid,
                        page == Page.MODERATORS, old.allowed && !old.busy && old.pending == null && old.completionPopCount == null && isCurrentSession())
                    savedState[KEY_SELECTED] = ArrayList(selected)
                    renderRows(old.copy(selectedIds = selected))
                }
            }
            is Intent.ChangeSetting -> if (canSubmit()) {
                val request = when (intent.setting) {
                    Setting.INVITATION -> ChangeGroupSettingsReq(invitationRule = if (intent.checked) 1 else 2)
                    Setting.PUBLISH -> ChangeGroupSettingsReq(publishRule = if (intent.checked) 1 else 2)
                    Setting.CRITICAL_ALERT -> ChangeGroupSettingsReq(criticalAlert = intent.checked)
                }
                submit(GroupMutationRepository.Change.Settings(request), intent.setting to intent.checked)
            }
            Intent.Submit -> if (canSubmit() && _state.value.selectedIds.isNotEmpty()) {
                val snapshot = _state.value.selectedIds.toList()
                val change = when (page) {
                    Page.MANAGEMENT -> return
                    Page.MODERATORS -> when (moderatorAction) {
                        ModeratorAction.ADD -> GroupMutationRepository.Change.Role(snapshot.single(), GROUP_ROLE_ADMIN)
                        ModeratorAction.REMOVE -> GroupMutationRepository.Change.Role(snapshot.single(), GROUP_ROLE_MEMBER)
                        ModeratorAction.TRANSFER -> GroupMutationRepository.Change.Settings(ChangeGroupSettingsReq(owner = snapshot.single()))
                    }
                    Page.MEMBERS -> GroupMutationRepository.Change.Members(snapshot, memberAction == MemberAction.ADD)
                }
                submit(change)
            }
            Intent.Retry -> {
                val pending = _state.value.pending ?: return
                if (_state.value.busy) return
                _state.update { it.copy(busy = true, generation = it.generation + 1) }
                viewModelScope.launch { consume(mutations.retry(pending).await()) }
            }
        }
    }

    private fun canSubmit() = _state.value.let {
        it.loaded && it.allowed && !it.busy && it.pending == null && it.completionPopCount == null && isCurrentSession()
    }

    private fun submit(change: GroupMutationRepository.Change, acceptedSetting: Pair<Setting, Boolean>? = null) {
        _state.update { it.copy(busy = true, generation = it.generation + 1) }
        // The deferred is owned by the repository's application scope, not this page.
        val operation = mutations.submit(gid, change)
        viewModelScope.launch {
            val outcome = operation.await()
            if (outcome !is GroupMutationRepository.Outcome.Rejected && acceptedSetting != null) {
                _state.update { old ->
                    val accepted = old.acceptedSettings + acceptedSetting
                    old.copy(acceptedSettings = accepted,
                        invitation = accepted[Setting.INVITATION] ?: old.invitation,
                        publish = accepted[Setting.PUBLISH] ?: old.publish,
                        criticalAlert = accepted[Setting.CRITICAL_ALERT] ?: old.criticalAlert)
                }
            }
            consume(outcome)
        }
    }

    private suspend fun consume(outcome: GroupMutationRepository.Outcome) {
        if (!isCurrentSession()) {
            _state.update { it.copy(busy = false, allowed = false) }
            return
        }
        when (outcome) {
            is GroupMutationRepository.Outcome.Rejected -> {
                _state.update { it.copy(busy = false) }
                val addingMembers = page == Page.MEMBERS && memberAction == MemberAction.ADD
                val messageRes = when {
                    addingMembers && outcome.status == STATUS_MODERATORS_ONLY -> R.string.invite_only_moderators_can_add
                    addingMembers && outcome.status == STATUS_NOT_FRIEND -> R.string.group_not_your_friend
                    else -> null
                }
                _effect.send(Effect.Message(outcome.reason, messageRes = messageRes, messageArg = outcome.detail))
                load()
            }
            is GroupMutationRepository.Outcome.AppliedFollowupFailed -> {
                _state.update { it.copy(busy = false, pending = outcome.pending) }
                _effect.send(Effect.Message(accepted = true))
            }
            is GroupMutationRepository.Outcome.Applied -> {
                _state.update { it.copy(busy = false, pending = null) }
                when {
                    page == Page.MEMBERS && outcome.pending.change is GroupMutationRepository.Change.Members &&
                        outcome.pending.change.add == (memberAction == MemberAction.ADD) ->
                        _state.update { it.copy(completionPopCount = 1, allowed = false) }
                    page == Page.MODERATORS && moderatorAction == ModeratorAction.TRANSFER &&
                        (outcome.pending.change as? GroupMutationRepository.Change.Settings)?.request?.owner != null ->
                        _state.update { it.copy(completionPopCount = 2, allowed = false) }
                    else -> {
                        if (outcome.pending.change is GroupMutationRepository.Change.Role) {
                            _effect.send(Effect.Message(messageRes = R.string.operation_successful))
                        }
                        load(refresh = false)
                    }
                }
            }
        }
    }

    private fun load(refresh: Boolean = true) {
        val generation = _state.value.generation + 1
        _state.update { it.copy(generation = generation) }
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    if (!isCurrentSession()) return@withContext null
                    if (refresh) groupUtil.fetchAndSaveSingleGroupInfo(gid, expectedSession =
                        creatorLogin?.let { GroupWriteSession(accountId, it) })
                    if (!isCurrentSession()) return@withContext null
                    val group = store.snapshot(gid) ?: return@withContext null
                    val members = group.members
                    val self = members.firstOrNull { it.uid == accountId }
                    val allowed = group.status == 0 && self != null && when (page) {
                        Page.MANAGEMENT, Page.MODERATORS -> self.role == GROUP_ROLE_OWNER
                        Page.MEMBERS -> memberAction == MemberAction.REMOVE || group.invitationRule == 2 || self.role <= GROUP_ROLE_ADMIN
                    }
                    val existing = members.mapNotNullTo(mutableSetOf()) { it.uid }
                    val rows: List<GroupMemberModel> = when (page) {
                        Page.MANAGEMENT -> {
                            val moderators = members.filter { it.role <= GROUP_ROLE_ADMIN }
                                .sortedByRoleThenPinyin().toMutableList()
                            if (moderators.size < members.size) moderators += GroupMemberModel("", "+", "", "", "")
                            if (moderators.count { it.uid != "+" } > 1) moderators += GroupMemberModel("", "-", "", "", "")
                            moderators
                        }
                        Page.MODERATORS -> members.filter {
                            when (moderatorAction) {
                                ModeratorAction.ADD -> it.role == GROUP_ROLE_MEMBER
                                ModeratorAction.REMOVE -> it.role == GROUP_ROLE_ADMIN
                                ModeratorAction.TRANSFER -> it.role != GROUP_ROLE_OWNER
                            }
                        }.sortedByRoleThenPinyin()
                        Page.MEMBERS -> if (memberAction == MemberAction.ADD) {
                            store.contacts().filterNot { it.uid.orEmpty().isOfficialAccount() || it.uid == accountId }
                                .map { it.copy(isSelected = it.uid in existing, checkBoxEnable = it.uid !in existing) }.sortedByPinyin()
                        } else members.map { contact ->
                            val role = contact.role
                            val operate = self != null && (self.role < role ||
                                (group.anyoneRemove == true && role == GROUP_ROLE_MEMBER && contact.uid != accountId))
                            contact.copy(showCheckBox = operate, checkBoxEnable = operate)
                        }.sortedByRoleThenPinyin()
                    }
                    Loaded(rows, allowed, group.status != 0, group.invitationRule == 1,
                        group.publishRule == 1, group.criticalAlert == true)
                }
                if (_state.value.generation != generation || !isCurrentSession()) return@launch
                if (loaded == null) {
                    // Absence after a failed/deduplicated fetch is not evidence of deletion.
                    _state.update { it.copy(loaded = true, allowed = false) }
                    return@launch
                }
                _state.update { old ->
                    val eligible = loaded.rows.filter { it.showCheckBox && it.checkBoxEnable }.mapNotNullTo(mutableSetOf()) { it.uid }
                    val selected = old.selectedIds.intersect(eligible)
                    savedState[KEY_SELECTED] = ArrayList(selected)
                    val pending = old.pending ?: mutations.pendingFor(gid, accountId)
                    val request = (pending?.change as? GroupMutationRepository.Change.Settings)?.request
                    val acceptedValues = old.acceptedSettings.toMutableMap().apply {
                        request?.invitationRule?.let { put(Setting.INVITATION, it == 1) }
                        request?.publishRule?.let { put(Setting.PUBLISH, it == 1) }
                        request?.criticalAlert?.let { put(Setting.CRITICAL_ALERT, it) }
                    }
                    val accepted = acceptedValues.filter { (setting, value) ->
                        value != when (setting) {
                            Setting.INVITATION -> loaded.invitation
                            Setting.PUBLISH -> loaded.publish
                            Setting.CRITICAL_ALERT -> loaded.criticalAlert
                        }
                    }
                    renderRows(old.copy(allRows = loaded.rows, eligibleIds = eligible, selectedIds = selected,
                        loaded = true, allowed = loaded.allowed, closeSubject = loaded.removed,
                        pending = pending, acceptedSettings = accepted,
                        invitation = accepted[Setting.INVITATION] ?: loaded.invitation,
                        publish = accepted[Setting.PUBLISH] ?: loaded.publish,
                        criticalAlert = accepted[Setting.CRITICAL_ALERT] ?: loaded.criticalAlert))
                }
                if (loaded.removed) _effect.send(Effect.CloseSubject)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[GroupPage] event=load_failed ${e.stackTraceToString()}" }
                if (_state.value.generation == generation) _effect.send(Effect.Message())
            }
        }
    }

    private fun renderRows(state: State): State = state.copy(rows = state.allRows
        .filter { it.matches(state.query) }
        .map { it.copy(isSelected = if (it.checkBoxEnable) it.uid in state.selectedIds else it.isSelected) })

    /** Display name, raw contact names (remark, publicName, name) and uid, case-insensitive like the contactor search. */
    private fun GroupMemberModel.matches(query: String): Boolean = query.isBlank() ||
        (listOfNotNull(name, uid) + searchNames).any { it.contains(query, ignoreCase = true) }

    private data class Loaded(val rows: List<GroupMemberModel>, val allowed: Boolean, val removed: Boolean,
        val invitation: Boolean, val publish: Boolean, val criticalAlert: Boolean)

    companion object {
        const val ARG_GID = "group_id"
        const val ARG_PAGE = "group_page"
        const val ARG_ACTION = "group_action"
        const val ARG_TYPE = "group_type"
        private const val STATUS_MODERATORS_ONLY = 2
        private const val STATUS_NOT_FRIEND = 10125
        private const val KEY_SELECTED = "selected_ids"
        private const val KEY_QUERY = "query"
    }
}
