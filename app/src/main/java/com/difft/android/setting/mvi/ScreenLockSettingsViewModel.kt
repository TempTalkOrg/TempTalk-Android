package com.difft.android.setting.mvi

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.difft.android.setting.mvi.ScreenLockContract.Action
import com.difft.android.setting.mvi.ScreenLockContract.Intent
import com.difft.android.setting.mvi.ScreenLockContract.Kind
import com.difft.android.setting.mvi.ScreenLockContract.Pending
import com.difft.android.setting.mvi.ScreenLockContract.Stage
import com.difft.android.setting.repo.ScreenLockSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScreenLockSettingsViewModel @Inject constructor(
    private val repository: ScreenLockSettingsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(repository.read().copy(pending = restoredPending()))
    val state = _state.asStateFlow()
    private val _effect = Channel<ScreenLockContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    private fun restoredPending(): Pending? {
        val pending = saved.get<Pending>("pending")?.copy(verifiedInProcess = false) ?: return null
        if (pending.accountId != repository.accountId()) return null
        
        return if (pending.stage == Stage.ReadyForSetup && pending.requiresVerification) {
            pending.copy(stage = Stage.ReadyForVerification)
        } else if (pending.stage == Stage.ChoosingBinding) null else pending
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            Intent.Refresh -> refresh()
            is Intent.Toggle -> toggle(intent.kind, intent.enabled)
            is Intent.SetShowPath -> {
                if (state.value.pending == null) repository.setShowPath(intent.enabled)
                refresh()
            }
            is Intent.ChooseBinding -> state.value.pending?.takeIf { it.stage == Stage.ChoosingBinding }?.let {
                pending(it.copy(stage = Stage.ReadyForBinding, bindingType = intent.type))
            }
            Intent.DismissBinding -> if (state.value.pending?.stage == Stage.ChoosingBinding) { saved["promptDismissed"] = true; pending(null) }
            Intent.BeginBinding -> {
                saved["promptDismissed"] = true
                if (state.value.pending == null) pending(newPending(Kind.Passcode, Action.BindOnly, Stage.ChoosingBinding, false))
                _state.update { it.copy(promptBinding = false) }
            }
            is Intent.Result -> result(intent)
            Intent.DismissPrompt -> { saved["promptDismissed"] = true; _state.update { it.copy(promptBinding = false) } }
        }
    }

    
    fun takeVisibleLaunch(): Pending? {
        val request = state.value.pending ?: return null
        if (request.accountId != repository.accountId()) { pending(null); refresh(); return null }
        if (request.stage == Stage.ReadyForSetup && request.requiresVerification && !request.verifiedInProcess) {
            pending(request.copy(stage = Stage.ReadyForVerification))
            return null
        }
        val awaiting = when (request.stage) {
            Stage.ReadyForBinding -> Stage.AwaitingBinding
            Stage.ReadyForVerification -> Stage.AwaitingVerification
            Stage.ReadyForSetup -> Stage.AwaitingSetup
            else -> return null
        }
        pending(request.copy(stage = awaiting))
        return request
    }

    private fun refresh() {
        val old = state.value
        val pending = old.pending?.takeIf { it.accountId == repository.accountId() }
        val truth = repository.read()
        _state.update { truth.copy(
            pending = pending,
            promptBinding = pending == null && (truth.pattern || truth.passcode) && repository.needsBinding() && saved.get<Boolean>("promptDismissed") != true,
        ) }
        saved["pending"] = pending
    }

    private fun toggle(kind: Kind, enabled: Boolean) {
        refresh()
        if (state.value.pending != null) return
        val truth = state.value
        if ((if (kind == Kind.Pattern) truth.pattern else truth.passcode) == enabled) return
        val verify = !enabled || truth.pattern || truth.passcode
        val stage = if (verify) Stage.ReadyForVerification else if (repository.needsBinding()) Stage.ChoosingBinding else Stage.ReadyForSetup
        pending(newPending(kind, if (enabled) Action.Enable else Action.Delete, stage, verify))
    }

    private fun newPending(kind: Kind, action: Action, stage: Stage, verify: Boolean) =
        Pending(UUID.randomUUID().toString(), repository.accountId(), kind, action, stage, verify)

    private fun pending(value: Pending?) {
        saved["pending"] = value
        _state.update { it.copy(pending = value, promptBinding = false) }
    }

    private fun result(result: Intent.Result) {
        val request = state.value.pending ?: return
        if (request.requestId != result.requestId || request.stage != result.stage) return
        if (request.accountId != repository.accountId()) { pending(null); refresh(); return }
        when (request.stage) {
            Stage.AwaitingBinding -> {
                if (result.success && !repository.needsBinding() && request.action != Action.BindOnly) pending(request.copy(stage = if (request.requiresVerification && !request.verifiedInProcess) Stage.ReadyForVerification else Stage.ReadyForSetup))
                else pending(null)
            }
            Stage.AwaitingVerification -> {
                if (!result.success) pending(null)
                else if (request.action == Action.Delete) {
                    
                    if (repository.disable(request.accountId, request.kind)) _effect.trySend(ScreenLockContract.Effect.Disabled(request.kind))
                    pending(null)
                } else pending(request.copy(stage = if (repository.needsBinding()) Stage.ChoosingBinding else Stage.ReadyForSetup, verifiedInProcess = true))
            }
            Stage.AwaitingSetup -> pending(null) 
            else -> return
        }
        refresh()
    }
}
