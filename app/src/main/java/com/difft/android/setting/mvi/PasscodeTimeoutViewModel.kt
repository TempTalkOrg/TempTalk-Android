package com.difft.android.setting.mvi

import androidx.lifecycle.ViewModel
import com.difft.android.login.PasscodeUtil
import com.difft.android.setting.repo.ScreenLockSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

object PasscodeTimeoutContract {
    data class State(val selected: Int? = null)
    sealed interface Intent {
        data object Refresh : Intent
        data class Select(val seconds: Int) : Intent
    }
}

@HiltViewModel
class PasscodeTimeoutViewModel @Inject constructor(private val repository: ScreenLockSettingsRepository) : ViewModel() {
    private val _state = MutableStateFlow(PasscodeTimeoutContract.State(repository.read().timeout))
    val state = _state.asStateFlow()
    fun dispatch(intent: PasscodeTimeoutContract.Intent) {
        when (intent) {
            PasscodeTimeoutContract.Intent.Refresh -> _state.update { it.copy(selected = repository.read().timeout) }
            is PasscodeTimeoutContract.Intent.Select -> if (intent.seconds in PasscodeUtil.TIMEOUT_LIST) {
                repository.setTimeout(intent.seconds)
                _state.update { it.copy(selected = repository.read().timeout) }
            }
        }
    }
}
