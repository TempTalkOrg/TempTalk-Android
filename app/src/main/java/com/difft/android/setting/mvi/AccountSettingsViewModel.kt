package com.difft.android.setting.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserData
import com.difft.android.base.user.UserManager
import com.difft.android.setting.repo.AccountMutationRepository
import com.difft.android.setting.repo.AccountMutationResult
import com.difft.android.setting.repo.IdentityRotationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object AccountSettingsContract {
    data class State(val user: UserData? = null, val busy: Boolean = false)
    sealed interface Intent {
        data object Refresh : Intent
        
        data object Reload : Intent
        data class SetSearch(val enabled: Boolean) : Intent
        data class Logout(val beforeLogout: () -> Unit) : Intent
        data object RenewIdentity : Intent
    }
    sealed interface Effect {
        data object Applied : Effect
        data class Failed(val message: String? = null, val accepted: Boolean = false) : Effect
    }
}

@HiltViewModel
class AccountSettingsViewModel @Inject constructor(
    private val accounts: AccountMutationRepository,
    private val identity: IdentityRotationRepository,
    private val users: UserManager,
) : ViewModel() {
    private val _state = MutableStateFlow(AccountSettingsContract.State(user = users.getUserData()))
    val state = _state.asStateFlow()
    private val _effect = Channel<AccountSettingsContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: AccountSettingsContract.Intent) {
        when (intent) {
            AccountSettingsContract.Intent.Refresh -> viewModelScope.launch {
                try { accounts.refresh() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    
                    L.w { "[Account] refresh_failed ${e.stackTraceToString()}" }
                }
                _state.update { it.copy(user = users.getUserData()) }
            }
            AccountSettingsContract.Intent.Reload -> _state.update { it.copy(user = users.getUserData()) }
            is AccountSettingsContract.Intent.SetSearch -> perform { accounts.setSearch(intent.enabled) }
            is AccountSettingsContract.Intent.Logout -> perform { accounts.logout(intent.beforeLogout) }
            AccountSettingsContract.Intent.RenewIdentity -> perform(identityOperation = true) { identity.renewOrComplete() }
        }
    }

    private fun perform(identityOperation: Boolean = false, action: suspend () -> AccountMutationResult) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                when (val result = action()) {
                    AccountMutationResult.Applied -> _effect.send(AccountSettingsContract.Effect.Applied)
                    AccountMutationResult.StaleAccount, AccountMutationResult.Busy -> Unit
                    is AccountMutationResult.Rejected -> _effect.send(AccountSettingsContract.Effect.Failed(result.message))
                    is AccountMutationResult.AcceptedLocalCommitFailed -> _effect.send(AccountSettingsContract.Effect.Failed(accepted = true))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                L.e { "[Account] mutation_failed identity=$identityOperation ${e.stackTraceToString()}" }
                val message = if (identityOperation && e is retrofit2.HttpException && e.code() == 413) {
                    com.difft.android.base.utils.ResUtils.getString(com.difft.android.R.string.settings_new_key_times_limit_tips)
                } else e.message
                _effect.send(AccountSettingsContract.Effect.Failed(message))
            }
            finally { _state.update { it.copy(busy = false, user = users.getUserData()) } }
        }
    }
}
