package com.difft.android.setting.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.R
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.UserManager
import com.difft.android.base.utils.ResUtils
import com.difft.android.base.utils.globalServices
import com.difft.android.setting.repo.SettingRepo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

object SetCustomIdContract {
    data class State(val input: String = "", val initialized: Boolean = false, val error: String? = null, val busy: Boolean = false, val completed: Boolean = false, val valid: Boolean = false)
    sealed interface Intent {
        data class Initialize(val value: String) : Intent
        data class ChangeInput(val value: String) : Intent
        data object Save : Intent
    }
    sealed interface Effect { data object Saved : Effect }
}

@HiltViewModel
class SetCustomIdViewModel @Inject constructor(
    private val settings: SettingRepo,
    private val users: UserManager,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow(SetCustomIdContract.State(input = saved["input"] ?: "", initialized = saved["initialized"] ?: false, completed = saved["completed"] ?: false, valid = validateCustomId(saved.get<String>("input").orEmpty()) == null))
    val state = _state.asStateFlow()
    private val _effect = Channel<SetCustomIdContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: SetCustomIdContract.Intent) {
        when (intent) {
            is SetCustomIdContract.Intent.Initialize -> if (!state.value.initialized) {
                saved["initialized"] = true
                saved["input"] = intent.value
                _state.update { it.copy(input = intent.value, initialized = true, valid = validateCustomId(intent.value) == null) }
            }
            is SetCustomIdContract.Intent.ChangeInput -> if (!state.value.busy) {
                saved["input"] = intent.value
                _state.update { it.copy(input = intent.value, valid = validateCustomId(intent.value) == null, error = validateCustomId(intent.value)?.let { ResUtils.getString(it) }) }
            }
            SetCustomIdContract.Intent.Save -> save()
        }
    }

    private fun save() {
        val request = state.value
        if (request.busy || request.completed) return
        val validation = validateCustomId(request.input)
        if (validation != null) { _state.update { it.copy(error = ResUtils.getString(validation)) }; return }
        val account = globalServices.myId
        val authorization = users.getUserData()?.baseAuth
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val response = settings.setProfile(customUid = request.input)
                if (account != globalServices.myId || users.getUserData()?.baseAuth != authorization) return@launch
                if (response.status == 0) {
                    users.update {
                        if (this.account == account && baseAuth == authorization) customUid = request.input
                    }
                    saved["completed"] = true
                    _state.update { it.copy(completed = true) }
                } else {
                    L.w { "[Account] custom_id_rejected status=${response.status}" }
                    val message = when (response.status) {
                        10301 -> ResUtils.getString(R.string.settings_contact_ID_status_uid_exists, response.data?.recommendUid)
                        10302 -> ResUtils.getString(R.string.settings_contact_ID_status_uid_limited)
                        else -> response.reason
                    }
                    _state.update { it.copy(error = message) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                L.e { "[Account] custom_id_failed ${e.stackTraceToString()}" }
                _state.update { it.copy(error = ResUtils.getString(R.string.settings_contact_ID_status_failed)) }
            }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
}

internal fun validateCustomId(value: String): Int? = when {
    value.length !in 6..20 -> R.string.settings_contact_ID_status_uid_length_invalid
    value.first() !in 'a'..'z' && value.first() !in 'A'..'Z' && value.first() != '_' -> R.string.settings_contact_ID_status_uid_invalid_1
    value.any { it !in 'a'..'z' && it !in 'A'..'Z' && it !in '0'..'9' && it != '_' && it != '-' } -> R.string.settings_contact_ID_status_uid_invalid_2
    else -> null
}
