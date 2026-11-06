package com.difft.android.setting.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.globalServices
import com.difft.android.base.utils.pane.PageOperations
import com.difft.android.messageserialization.db.store.formatBase58Id
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.setting.repo.AccountMutationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object DeleteAccountContract {
    data class State(val input: String = "", val suffix: String = "", val busy: Boolean = false)
    sealed interface Intent {
        data class ChangeInput(val value: String) : Intent
        data class Confirm(val beforeLogout: () -> Unit) : Intent
    }
    sealed interface Effect { data class Failed(val message: String?) : Effect }
}

@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val repository: AccountMutationRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val accountId = globalServices.myId
    private val _state = MutableStateFlow(DeleteAccountContract.State(
        input = saved["input"] ?: "", suffix = accountId.formatBase58Id(false).takeLast(6),
        busy = PageOperations.running("delete-account:$accountId").value,
    ))
    val state = _state.asStateFlow()
    private val _effect = Channel<DeleteAccountContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    init {
        viewModelScope.launch {
            PageOperations.running("delete-account:$accountId").collect { busy -> _state.update { it.copy(busy = busy) } }
        }
    }

    fun dispatch(intent: DeleteAccountContract.Intent) {
        when (intent) {
            is DeleteAccountContract.Intent.ChangeInput -> if (!state.value.busy) {
                saved["input"] = intent.value
                _state.update { it.copy(input = intent.value) }
            }
            is DeleteAccountContract.Intent.Confirm -> {
                val request = state.value
                if (request.busy || request.input.trim() != request.suffix || accountId != globalServices.myId) return
                _state.update { it.copy(busy = true) }
                viewModelScope.launch {
                    try {
                        when (val result = repository.deleteAccount(intent.beforeLogout)) {
                            AccountMutationResult.Applied, AccountMutationResult.StaleAccount, AccountMutationResult.Busy -> Unit
                            is AccountMutationResult.Rejected -> _effect.send(DeleteAccountContract.Effect.Failed(result.message))
                            is AccountMutationResult.AcceptedLocalCommitFailed -> _effect.send(DeleteAccountContract.Effect.Failed(null))
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        L.e { "[Account] delete_account_failed ${e.stackTraceToString()}" }
                        _effect.send(DeleteAccountContract.Effect.Failed(e.message))
                    }
                    finally { _state.update { it.copy(busy = false) } }
                }
            }
        }
    }
}
