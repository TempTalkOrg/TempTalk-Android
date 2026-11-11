package com.difft.android.chat.focus.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusResult
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupNotifySettingsReader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject


sealed interface NotifyFocusCaption {
    data object Off : NotifyFocusCaption
    data class Focusing(val name: String) : NotifyFocusCaption

    
    data object Invalid : NotifyFocusCaption
}

object NotifyFocusSettingsContract {

    sealed interface Intent {
        
        data object Refresh : Intent
        data class ToggleFocus(val requested: Boolean) : Intent
        data object TapEndTime : Intent
        data object TapOthersStillNotify : Intent
        data class SetFoldOthers(val requested: Boolean) : Intent

        
        data object TapSelectGroup : Intent
        data object TapGroupNotificationPaused : Intent
    }

    data class State(
        
        val featureEnabled: Boolean = false,
        
        val focusOn: Boolean = false,
        val caption: NotifyFocusCaption = NotifyFocusCaption.Off,
        val invalid: Boolean = false,
        val end: FocusEnd? = null,
        val othersStillNotify: List<StillNotifyItem> = emptyList(),
        val foldOthers: Boolean = true,
        
        val groupLevel: GlobalNotificationType = GlobalNotificationType.ALL,
        val groupNotificationPaused: Boolean = false,
    )

    sealed interface Effect {
        data object OpenGroupPicker : Effect
        data object EndFocus : Effect
        data object ChangeEndTime : Effect
        data object OpenOthersStillNotify : Effect

        
        data object ShowPausedDialog : Effect
        data object ShowError : Effect
    }
}


@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotifyFocusSettingsViewModel internal constructor(
    private val repository: GroupFocusRepository,
    private val groups: FocusGroupDirectory,
    private val reader: GroupNotifySettingsReader,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        repository: GroupFocusRepository,
        groups: FocusGroupDirectory,
        reader: GroupNotifySettingsReader,
    ) : this(repository, groups, reader, ServerTimeProvider::nowMillis)

    private val level = MutableStateFlow(GlobalNotificationType.ALL)

    private val focusGroupName = repository.effective
        .map { it.focusGid }
        .distinctUntilChanged()
        .mapLatest { gid -> gid?.let { groups.name(it) }.orEmpty() }

    val state: StateFlow<NotifyFocusSettingsContract.State> = combine(repository.effective, level, focusGroupName, ::derive)
        .stateIn(viewModelScope, SharingStarted.Eagerly, NotifyFocusSettingsContract.State())

    private val _effect = Channel<NotifyFocusSettingsContract.Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: NotifyFocusSettingsContract.Intent) {
        when (intent) {
            NotifyFocusSettingsContract.Intent.Refresh -> level.value = reader.globalLevel()
            is NotifyFocusSettingsContract.Intent.ToggleFocus -> _effect.trySend(
                if (intent.requested) NotifyFocusSettingsContract.Effect.OpenGroupPicker else NotifyFocusSettingsContract.Effect.EndFocus
            )

            NotifyFocusSettingsContract.Intent.TapEndTime -> _effect.trySend(NotifyFocusSettingsContract.Effect.ChangeEndTime)
            NotifyFocusSettingsContract.Intent.TapOthersStillNotify -> _effect.trySend(NotifyFocusSettingsContract.Effect.OpenOthersStillNotify)
            is NotifyFocusSettingsContract.Intent.SetFoldOthers -> setFoldOthers(intent.requested)
            NotifyFocusSettingsContract.Intent.TapSelectGroup -> _effect.trySend(NotifyFocusSettingsContract.Effect.OpenGroupPicker)
            NotifyFocusSettingsContract.Intent.TapGroupNotificationPaused -> _effect.trySend(NotifyFocusSettingsContract.Effect.ShowPausedDialog)
        }
    }

    private fun setFoldOthers(requested: Boolean) {
        viewModelScope.launch {
            val result = repository.updateOptions(foldOthers = requested)
            if (result is FocusResult.Failure) {
                L.w { "[FocusSettings] fold update failed error=${result.error}" }
                _effect.trySend(NotifyFocusSettingsContract.Effect.ShowError)
            }
        }
    }

    private fun derive(focus: EffectiveFocus, level: GlobalNotificationType, focusName: String): NotifyFocusSettingsContract.State {
        val on = focus.featureEnabled && focus.active
        return NotifyFocusSettingsContract.State(
            featureEnabled = focus.featureEnabled,
            focusOn = on,
            caption = when {
                !on -> NotifyFocusCaption.Off
                focus.invalid -> NotifyFocusCaption.Invalid
                else -> NotifyFocusCaption.Focusing(focusName)
            },
            invalid = on && focus.invalid,
            end = focus.endLabel(clock()).takeIf { on },
            othersStillNotify = focus.othersStillNotify(),
            foldOthers = focus.foldOthers,
            groupLevel = level,
            groupNotificationPaused = on,
        )
    }
}
