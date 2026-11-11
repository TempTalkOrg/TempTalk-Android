package com.difft.android.chat.contacts.contactsdetail.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.chat.contacts.CardPreloads
import com.difft.android.chat.contacts.CountdownTicker
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.RoomChangeTracker
import com.difft.android.base.utils.RoomChangeType
import com.difft.android.chat.contacts.contactsdetail.ContactDetailRepository
import com.difft.android.chat.contacts.contactsdetail.ContactDetailUiState
import com.difft.android.chat.contacts.contactsdetail.contactCardPresentation
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.Intent
import com.difft.android.chat.contacts.contactsdetail.mvi.ContactDetailContract.State
import com.difft.android.chat.contacts.data.ContactorUtil
import com.difft.android.chat.setting.ConversationSettingsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.difft.app.database.cache.OfficialAccountCache
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    private val repository: ContactDetailRepository,
    private val settings: ConversationSettingsManager,
    private val countdownTicker: CountdownTicker,
    private val preloads: CardPreloads,
) : ViewModel() {
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val _effect = Channel<ContactDetailContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        viewModelScope.launch {
            ContactorUtil.contactsUpdate.collect { ids ->
                if (_state.value.input?.let { it.contactId in ids } == true) refresh(remote = false)
            }
        }
        viewModelScope.launch {
            OfficialAccountCache.state.drop(1).collect { if (_state.value.input != null) refresh(remote = false) }
        }
        viewModelScope.launch {
            settings.conversationSettingUpdate.collect {
                if (it.conversationId == _state.value.input?.contactId) refresh(remote = false)
            }
        }
        viewModelScope.launch {
            
            RoomChangeTracker.roomChanges.collect { batch ->
                val id = _state.value.input?.contactId
                if (batch.any { it.type == RoomChangeType.REFRESH && it.roomId == id }) refresh(remote = false)
            }
        }
        viewModelScope.launch {
            
            countdownTicker.dayChanges.collect { refresh(remote = false) }
        }
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Load -> {
                if (_state.value.input == intent.input) return
                val preloaded = preloads.take<ContactDetailUiState>(intent.preloadKey)
                _state.update { it.copy(input = intent.input, fetched = null,
                    ui = preloaded ?: ContactDetailUiState(isSelf = repository.isSelf(intent.input.contactId))) }
                refresh(remote = true)
            }
            
            Intent.Refresh -> refresh(remote = false)
            Intent.AddFriend -> addFriend()
            Intent.RemoveNow -> removeNow()
        }
    }

    private fun addFriend() {
        val snapshot = _state.value
        val input = snapshot.input ?: return
        if (snapshot.submitting || snapshot.ui.presentation.kind !in listOf(
                com.difft.android.chat.contacts.contactsdetail.ContactCardKind.STRANGER,
                com.difft.android.chat.contacts.contactsdetail.ContactCardKind.PENDING_REMOVAL)) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            _effect.send(ContactDetailContract.Effect.ShowWait)
            try {
                val result = repository.addFriend(input, viewModelScope)
                _effect.send(when (result.status) {
                    0 -> ContactDetailContract.Effect.FriendAdded
                    19009 -> ContactDetailContract.Effect.AccountUnavailable(snapshot.ui.presentation.showDelete)
                    else -> ContactDetailContract.Effect.Failed(result.reason)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effect.send(ContactDetailContract.Effect.Failed(e.message))
            } finally {
                _state.update { it.copy(submitting = false) }
                _effect.trySend(ContactDetailContract.Effect.DismissWait)
            }
        }
    }

    private fun removeNow() {
        val snapshot = _state.value
        val input = snapshot.input ?: return
        if (snapshot.submitting || !snapshot.ui.presentation.showDelete) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            try {
                repository.removeNow(input.contactId)
                    .onSuccess { _effect.send(ContactDetailContract.Effect.Removed) }
                    .onFailure { _effect.send(ContactDetailContract.Effect.RemoveFailed) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _effect.send(ContactDetailContract.Effect.RemoveFailed)
            } finally {
                _state.update { it.copy(submitting = false) }
            }
        }
    }

    private fun refresh(remote: Boolean) {
        val input = _state.value.input ?: return
        _state.update { it.copy(generation = it.generation + 1) }
        val generation = _state.value.generation
        viewModelScope.launch {
            try {
                val local = repository.load(input, fetched = _state.value.fetched)
                if (_state.value.generation == generation) _state.update { it.copy(ui = local) }
                if (!remote) return@launch
                
                
                val fetchStartedAt = System.nanoTime()
                val contact = repository.refresh(input) ?: return@launch
                L.i { "[ContactCard] server_profile id=${input.contactId} ms=${(System.nanoTime() - fetchStartedAt) / NANOS_PER_MS}" }
                if (_state.value.input != input) return@launch
                _state.update { it.copy(fetched = contact) }
                launch { repository.ensureOriginalAvatarCached(contact) }
                if (local.contactor != contact) repository.notifyContactChanged(input.contactId)
                if (_state.value.generation != generation) {
                    refresh(remote = false)
                    return@launch
                }
                val updated = repository.load(input, contact)
                if (_state.value.generation == generation) _state.update { it.copy(ui = updated) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e { "[ContactCard] load_failed error=${e.stackTraceToString()}" }
                
                
                if (_state.value.generation == generation && !_state.value.ui.isRelationResolved) {
                    _state.update { it.copy(ui = it.ui.copy(isFriend = true, isRelationResolved = true,
                        presentation = contactCardPresentation(true, true, false, it.ui.isSelf, it.ui.isOfficialAccount))) }
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
