package com.difft.android.setting.mvi

import androidx.lifecycle.ViewModel
import com.difft.android.base.user.UserManager
import com.difft.android.base.user.NotificationContentDisplayType
import com.difft.android.call.data.VoicePreset
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object LocalPreferenceContract {
    /** [closeRequested] stays set until the page is actually removed, so a fold before the pop commits cannot lose it. */
    data class State(val speed: Float, val voice: VoicePreset, val displayType: Int, val closeRequested: Boolean = false)
    sealed interface Intent {
        data class SelectSpeed(val value: Float) : Intent
        data class SelectVoice(val value: VoicePreset) : Intent
        data class SelectDisplay(val value: Int) : Intent
    }
}

/** Local preference truth is owned by the page VM and stored before its close request (#1457). */
@HiltViewModel
class LocalPreferenceViewModel @Inject constructor(private val userManager: UserManager) : ViewModel() {
    private val _state = MutableStateFlow(userManager.getUserData().let { user ->
        LocalPreferenceContract.State(user?.voicePlaybackSpeed ?: 1f,
            VoicePreset.fromSdkKey(user?.callVoiceChangerPreset ?: VoicePreset.ORIGINAL.sdkKey),
            user?.notificationContentDisplayType ?: 0)
    })
    val state = _state.asStateFlow()

    fun dispatch(intent: LocalPreferenceContract.Intent) {
        when (intent) {
            is LocalPreferenceContract.Intent.SelectSpeed -> {
                if (intent.value !in setOf(1f, 1.5f, 2f)) return
                userManager.update { voicePlaybackSpeed = intent.value }
                _state.update { it.copy(speed = intent.value) }
            }
            is LocalPreferenceContract.Intent.SelectVoice -> {
                userManager.update { callVoiceChangerPreset = intent.value.sdkKey }
                _state.update { it.copy(voice = intent.value) }
            }
            is LocalPreferenceContract.Intent.SelectDisplay -> {
                if (NotificationContentDisplayType.entries.none { it.value == intent.value }) return
                userManager.update { notificationContentDisplayType = intent.value }
                _state.update { it.copy(displayType = intent.value, closeRequested = true) }
            }
        }
    }
}
