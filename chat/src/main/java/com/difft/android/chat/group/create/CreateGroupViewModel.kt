package com.difft.android.chat.group.create

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.chat.R
import com.difft.android.chat.common.AvatarPickTempCleaner
import com.difft.android.chat.common.AvatarUtil
import com.difft.android.chat.common.GroupAvatarUtil
import com.difft.android.chat.common.LetterItem
import com.difft.android.chat.contacts.contactsall.sortedByPinyin
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.contacts.data.getContactAvatarData
import com.difft.android.chat.contacts.data.getContactAvatarUrl
import com.difft.android.chat.contacts.data.getFirstLetter
import com.difft.android.chat.contacts.data.isOfficialAccount
import com.difft.android.chat.group.GroupMemberModel
import com.difft.android.chat.group.create.CreateGroupContract.Effect
import com.difft.android.chat.group.create.CreateGroupContract.Intent
import com.difft.android.chat.group.create.CreateGroupContract.State
import com.difft.android.chat.group.create.CreateGroupOperation.Outcome
import com.difft.android.messageserialization.db.store.getDisplayNameForUI
import com.difft.android.messageserialization.db.store.getDisplayNameWithoutRemarkForUI
import com.difft.android.messageserialization.db.store.getEffectiveAvatarJson
import com.difft.android.network.config.GlobalConfigsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.difft.app.database.WCDB
import org.difft.app.database.getContactorsFromAllTable
import org.difft.app.database.models.ContactorModel
import org.difft.app.database.search
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException







@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val wcdb: WCDB,
    private val operation: CreateGroupOperation,
    private val globalConfigsManager: GlobalConfigsManager,
) : ViewModel() {

    val preselectedIds: List<String> = savedState.get<ArrayList<String>>(ARG_PRESELECTED).orEmpty()

    



    private val restoredAvatar: String? = savedState.get<String>(KEY_AVATAR_PATH)?.takeIf { File(it).isFile }.also {
        if (it == null) {
            savedState[KEY_AVATAR_PATH] = null
            savedState[KEY_AVATAR_PICKED] = false
        }
    }

    private val _state = MutableStateFlow(
        State(
            selectedIds = savedState.get<ArrayList<String>>(KEY_SELECTED) ?: ArrayList(preselectedIds),
            query = savedState[KEY_QUERY] ?: "",
            name = savedState[KEY_NAME] ?: "",
            avatarPath = restoredAvatar,
            avatarPicked = restoredAvatar != null && savedState[KEY_AVATAR_PICKED] ?: false,
            completed = savedState[KEY_COMPLETED],
        )
    )
    val state = _state.asStateFlow()
    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private var contacts: List<ContactorModel> = emptyList()
    private var names: Map<String, String> = emptyMap()
    private var running: Deferred<Outcome>? = null
    private var generation: Job? = null
    
    private var handedOver: String? = null

    init {
        if (savedState.get<Boolean>(KEY_SUBMITTED) == true) {
            
            savedState[KEY_SUBMITTED] = false
            L.w { "[CreateGroup] result_lost reason=process_death" }
            viewModelScope.launch { _effect.send(Effect.Toast(R.string.group_create_result_unknown)) }
        }
        load()
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Load -> load()
            is Intent.Search -> {
                savedState[KEY_QUERY] = intent.query
                _state.update { it.copy(query = intent.query) }
                search(intent.query)
            }
            is Intent.Toggle -> toggle(intent.uid)
            is Intent.NameChanged -> {
                savedState[KEY_NAME] = intent.name
                _state.update { it.copy(name = intent.name) }
            }
            Intent.PickAvatar -> viewModelScope.launch { _effect.send(Effect.RequestPickAvatar) }
            is Intent.AvatarPicked -> adoptPicked(intent.path)
            Intent.Submit -> submit()
        }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val seeded = withContext(Dispatchers.IO) { wcdb.getContactorsFromAllTable(preselectedIds + globalServices.myId) }
                names = names + seeded.associate { it.id to it.getDisplayNameWithoutRemarkForUI() }
                if (savedState.get<Boolean>(KEY_NAME_SEEDED) != true) {
                    savedState[KEY_NAME_SEEDED] = true
                    if (preselectedIds.isNotEmpty()) {
                        val default = seeded.sortedBy { if (it.id == globalServices.myId) 0 else 1 }
                            .joinToString(", ") { it.getDisplayNameWithoutRemarkForUI() }
                        savedState[KEY_NAME] = default
                        _state.update { it.copy(name = default) }
                    }
                }
                search(_state.value.query)
                if (_state.value.avatarPath == null) regenerateAvatar()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[CreateGroup] load_failed ${e.stackTraceToString()}" }
            }
        }
    }

    private fun search(query: String) {
        viewModelScope.launch {
            try {
                val results = withContext(Dispatchers.IO) { wcdb.contactor.search(query.trim()) }
                contacts = results
                names = names + results.associate { it.id to it.getDisplayNameWithoutRemarkForUI() }
                _state.update { it.copy(rows = rows(results, it.selectedIds)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.w { "[CreateGroup] search_failed ${e.stackTraceToString()}" }
            }
        }
    }

    private fun rows(list: List<ContactorModel>, selected: List<String>): List<GroupMemberModel> =
        list.filterNot { it.id == globalServices.myId || it.id.isOfficialAccount() }
            .sortedByPinyin()
            .map { contact ->
                val avatar = contact.getEffectiveAvatarJson()?.getContactAvatarData()
                GroupMemberModel(
                    contact.getDisplayNameForUI(),
                    contact.id,
                    avatar?.getContactAvatarUrl(),
                    avatar?.encKey,
                    contact.getDisplayNameForUI().getFirstLetter(),
                    0,
                    isSelected = contact.id in selected,
                    checkBoxEnable = contact.id !in preselectedIds,
                    showCheckBox = true,
                    letterName = contact.getDisplayNameWithoutRemarkForUI(),
                )
            }

    private fun toggle(uid: String) {
        if (uid in preselectedIds || _state.value.busy || _state.value.completed != null) return
        val selected = _state.value.selectedIds.let { if (uid in it) it - uid else it + uid }
        savedState[KEY_SELECTED] = ArrayList(selected)
        _state.update { it.copy(selectedIds = selected, rows = rows(contacts, selected)) }
        
        if (!_state.value.avatarPicked && !_state.value.avatarAdopting) regenerateAvatar()
    }

    




    private fun regenerateAvatar() {
        generation?.cancel()
        generation = viewModelScope.launch {
            val selected = _state.value.selectedIds
            var produced: String? = null
            try {
                withContext(Dispatchers.IO) {
                    produced = if (selected.isEmpty()) null else runCatching {
                        
                        val letters = (listOf(globalServices.myId) + selected)
                            .mapNotNull { uid -> names[uid]?.takeIf { it.isNotEmpty() }?.let { uid to it } }
                            .take(MAX_AVATAR_LETTERS)
                            .map { (uid, name) -> LetterItem(ContactorUtil.getFirstLetter(name).first().uppercaseChar(), AvatarUtil.getBgColorResId(uid)) }
                        val used = letters.map { it.color }.toSet()
                        val background = AvatarUtil.colors.filterNot { it in used }.randomOrNull()
                            ?: context.getColor(com.difft.android.base.R.color.info)
                        GroupAvatarUtil.generateAvatarFile(letters, background)
                    }.onFailure { L.e { "[CreateGroup] avatar_generate_failed ${it.stackTraceToString()}" } }.getOrNull()
                }
            } catch (e: CancellationException) {
                produced?.let { deleteDraft(it) }
                throw e
            }
            val path = produced
            val current = _state.value
            if (current.avatarPicked || current.avatarAdopting || current.busy || current.completed != null) {
                path?.let { deleteDraft(it) }
                return@launch
            }
            current.avatarPath?.let { if (it != path && it != handedOver) deleteDraft(it) }
            savedState[KEY_AVATAR_PATH] = path
            savedState[KEY_AVATAR_PICKED] = false
            _state.update { it.copy(avatarPath = path, avatarPicked = false) }
        }
    }

    
    private fun adoptPicked(pickedPath: String) {
        generation?.cancel()
        _state.update { it.copy(avatarAdopting = true) }
        viewModelScope.launch {
            val draft = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(context.cacheDir, DRAFT_DIR).apply { mkdirs() }
                    val target = File(dir, "${UUID.randomUUID()}.${File(pickedPath).extension.ifEmpty { "jpg" }}")
                    File(pickedPath).copyTo(target, overwrite = true)
                    AvatarPickTempCleaner.deleteUploadedTemp(context, pickedPath)
                    target.absolutePath
                }.onFailure { L.e { "[CreateGroup] avatar_copy_failed ${it.stackTraceToString()}" } }.getOrNull()
            }
            if (draft == null) {
                _state.update { it.copy(avatarAdopting = false) }
                return@launch
            }
            _state.value.avatarPath?.let { if (it != handedOver) deleteDraft(it) }
            savedState[KEY_AVATAR_PATH] = draft
            savedState[KEY_AVATAR_PICKED] = true
            _state.update { it.copy(avatarPath = draft, avatarPicked = true, avatarAdopting = false) }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.busy || current.avatarAdopting || current.completed != null) return
        val session = operation.currentSession()
        if (session == null) {
            viewModelScope.launch { _effect.send(Effect.Toast(R.string.chat_net_error)) }
            return
        }
        
        val avatarPath = current.avatarPath?.takeIf { File(it).isFile }
        if (avatarPath == null && current.avatarPath != null) {
            L.w { "[CreateGroup] avatar_draft_missing picked=${current.avatarPicked}" }
            savedState[KEY_AVATAR_PATH] = null
            savedState[KEY_AVATAR_PICKED] = false
            _state.update { it.copy(avatarPath = null, avatarPicked = false) }
        }
        val draft = CreateGroupOperation.Draft(
            name = current.name.trim().ifEmpty { context.getString(R.string.new_group) },
            memberIds = current.selectedIds,
            avatarPath = avatarPath,
            encrypted = globalConfigsManager.isGroupEncryptionEnabled(),
        )
        L.i { "[CreateGroup] submit members=${draft.memberIds.size} encrypted=${draft.encrypted} avatar=${current.avatarPicked}" }
        
        generation?.cancel()
        handedOver = draft.avatarPath
        savedState[KEY_SUBMITTED] = true
        _state.update { it.copy(busy = true) }
        val deferred = operation.submit(draft, session)
        running = deferred
        viewModelScope.launch { consume(deferred.await()) }
    }

    private suspend fun consume(outcome: Outcome) {
        running = null
        when (outcome) {
            is Outcome.Created -> complete(outcome.gid)
            is Outcome.CreatedFollowupFailed -> {
                
                val retried = operation.retryFollowups(outcome.gid).await()
                if (retried !is Outcome.Created) {
                    L.w { "[CreateGroup] followup_unrecovered gid=${outcome.gid} stage=${(retried as? Outcome.CreatedFollowupFailed)?.stage}" }
                    operation.release(outcome.gid)
                    _effect.send(Effect.Toast(R.string.chat_net_error))
                }
                complete(outcome.gid)
            }
            is Outcome.Rejected -> {
                
                handedOver = null
                savedState[KEY_SUBMITTED] = false
                _state.update { it.copy(busy = false) }
                _effect.send(
                    when {
                        outcome.status == STATUS_NOT_FRIEND -> Effect.Toast(R.string.group_not_your_friend, arg = outcome.strangers.orEmpty())
                        
                        outcome.unknown -> Effect.Toast(R.string.group_create_result_unknown)
                        else -> Effect.Toast(text = outcome.reason?.takeIf { it.isNotBlank() }, messageRes = R.string.chat_net_error.takeIf { outcome.reason.isNullOrBlank() })
                    }
                )
            }
        }
    }

    
    private fun complete(gid: String) {
        savedState[KEY_COMPLETED] = gid
        savedState[KEY_SUBMITTED] = false
        _state.update { it.copy(busy = false, completed = gid) }
    }

    override fun onCleared() {
        generation?.cancel()
        
        _state.value.avatarPath?.let { if (it != handedOver) deleteDraft(it) }
        super.onCleared()
    }

    private fun deleteDraft(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    companion object {
        const val ARG_PRESELECTED = "preselected_ids"
        private const val KEY_SELECTED = "selected_ids"
        private const val KEY_QUERY = "query"
        private const val KEY_NAME = "name"
        private const val KEY_NAME_SEEDED = "name_seeded"
        private const val KEY_AVATAR_PATH = "avatar_path"
        private const val KEY_AVATAR_PICKED = "avatar_picked"
        private const val KEY_SUBMITTED = "submitted"
        private const val KEY_COMPLETED = "completed"
        private const val MAX_AVATAR_LETTERS = 6
        private const val STATUS_NOT_FRIEND = 10125
        const val DRAFT_DIR = "create_group"
        private const val DRAFT_MAX_AGE_MS = 24 * 60 * 60 * 1000L

        




        fun sweepStaleDrafts(context: Context, now: Long = System.currentTimeMillis()) {
            var deleted = 0
            var failed = 0
            runCatching {
                File(context.cacheDir, DRAFT_DIR).takeIf { it.isDirectory }
                    ?.listFiles { file -> file.isFile && now - file.lastModified() > DRAFT_MAX_AGE_MS }
                    ?.forEach { if (it.delete()) deleted++ else failed++ }
            }.onFailure { failed++ }
            if (deleted > 0 || failed > 0) L.i { "[CreateGroup] draft_sweep deleted=$deleted failed=$failed" }
        }
    }
}
