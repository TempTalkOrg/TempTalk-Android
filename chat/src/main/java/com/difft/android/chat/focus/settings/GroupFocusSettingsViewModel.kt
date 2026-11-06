package com.difft.android.chat.focus.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.user.GlobalNotificationType
import com.difft.android.base.utils.time.ServerTimeProvider
import com.difft.android.chat.focus.EffectiveFocus
import com.difft.android.chat.focus.FocusGroupDirectory
import com.difft.android.chat.focus.FocusResult
import com.difft.android.chat.focus.GroupFocusRepository
import com.difft.android.chat.focus.GroupNotifyPolicy.MuteOptions
import com.difft.android.chat.focus.GroupNotifySettingsReader
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Effect
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.Intent
import com.difft.android.chat.focus.settings.GroupFocusSettingsContract.State
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the group settings notification section: the focus card and the Mute / Notifications /
 * Sound card (PRD 7.1). The server focus snapshot comes from [GroupFocusRepository]; the group's
 * conversation config is fed by the host ([Intent.ConversationChanged]) because its write path
 * stays in the existing chat setting view model.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class GroupFocusSettingsViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: GroupFocusRepository,
    private val groups: FocusGroupDirectory,
    private val reader: GroupNotifySettingsReader,
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        repository: GroupFocusRepository,
        groups: FocusGroupDirectory,
        reader: GroupNotifySettingsReader,
    ) : this(savedStateHandle, repository, groups, reader, ServerTimeProvider::nowMillis)

    private val gid: String = savedStateHandle.get<String>(KEY_GROUP_ID).orEmpty()

    /** What the section shows besides the focus snapshot. */
    private data class Inputs(
        val groupName: String = "",
        val muted: Boolean = false,
        val muteOptions: MuteOptions = MuteOptions(),
        val level: GlobalNotificationType = GlobalNotificationType.ALL,
        val soundLabel: String? = null,
    )

    private val inputs = MutableStateFlow(Inputs())

    private val focusGroupName = repository.effective
        .map { it.focusGid }
        .distinctUntilChanged()
        .mapLatest { focusGid -> focusGid?.let { groups.name(it) }.orEmpty() }

    val state: StateFlow<State> = combine(repository.effective, inputs, focusGroupName, ::derive)
        .stateIn(viewModelScope, SharingStarted.Eagerly, State())

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.ConversationChanged -> inputs.update {
                it.copy(
                    muted = intent.muted,
                    muteOptions = MuteOptions(intent.allowMentionMe, intent.allowMentionAll, intent.allowGroupCall),
                )
            }

            Intent.Refresh -> refresh()
            is Intent.ToggleFocus -> toggleFocus(intent.requested)
            Intent.TapEndTime -> _effect.trySend(Effect.ChangeEndTime)
            Intent.TapOthersStillNotify -> _effect.trySend(Effect.OpenOthersStillNotify)
            is Intent.SetFoldOthers -> setFoldOthers(intent.requested)
            is Intent.ToggleMute -> _effect.trySend(Effect.WriteMute(intent.requested))
            Intent.TapMuteExceptions -> _effect.trySend(Effect.OpenMuteExceptions)
            Intent.TapMutePaused -> _effect.trySend(Effect.ShowPausedDialog(PausedDialog.MUTE_BY_FOCUS))
            Intent.TapNotification -> tapNotification()
            Intent.TapSound -> _effect.trySend(Effect.OpenSound(gid, inputs.value.groupName))
        }
    }

    private fun toggleFocus(requested: Boolean) {
        val focus = repository.effective.value
        when {
            requested -> _effect.trySend(Effect.RequestFocus(gid, inputs.value.groupName))
            focus.isFocusedGroup(gid) -> _effect.trySend(Effect.EndFocus)
        }
    }

    private fun setFoldOthers(requested: Boolean) {
        viewModelScope.launch {
            val result = repository.updateOptions(foldOthers = requested)
            if (result is FocusResult.Failure) {
                L.w { "[FocusSettings] fold update failed error=${result.error}" }
                _effect.trySend(Effect.ShowError)
            }
        }
    }

    private fun tapNotification() {
        val focus = repository.effective.value
        val dialog = when {
            focus.featureEnabled && focus.active -> PausedDialog.NOTIFY_BY_FOCUS
            focus.featureEnabled && inputs.value.muted -> PausedDialog.NOTIFY_BY_MUTE
            else -> null
        }
        _effect.trySend(if (dialog != null) Effect.ShowPausedDialog(dialog) else Effect.OpenNotificationLevel)
    }

    private fun refresh() {
        viewModelScope.launch {
            val name = groups.name(gid).orEmpty()
            val level = reader.level(gid)
            val sound = if (reader.soundSupported()) reader.soundLabel(gid).orEmpty() else null
            inputs.update { it.copy(groupName = name, level = level, soundLabel = sound) }
        }
    }

    private fun derive(focus: EffectiveFocus, input: Inputs, focusName: String): State {
        val enabled = focus.featureEnabled
        val focusOn = enabled && focus.isFocusedGroup(gid)
        val takeover = enabled && focus.active
        val muteRestore = enabled && !takeover && input.muted
        val end = focus.endLabel(clock())
        return State(
            featureEnabled = enabled,
            focusOn = focusOn,
            focusCaption = when {
                !takeover -> FocusCaption.Off
                focus.invalid -> FocusCaption.Invalid
                focusOn -> FocusCaption.OthersMuted
                else -> FocusCaption.OtherGroup(focusName, end ?: FocusEnd.Manual)
            },
            focusEnd = end.takeIf { focusOn },
            othersStillNotify = focus.othersStillNotify(),
            foldOthers = focus.foldOthers,
            muted = input.muted,
            mutePaused = takeover,
            showMuteExceptions = muteRestore,
            muteExceptions = input.muteOptions.stillNotifyItems(),
            level = input.level,
            notifyPaused = takeover || muteRestore,
            takeoverCaption = when {
                takeover -> TakeoverCaption.ByFocus(input.muted, input.level)
                muteRestore -> TakeoverCaption.ByMute(input.level)
                else -> TakeoverCaption.None
            },
            soundVisible = input.soundLabel != null,
            soundLabel = input.soundLabel,
        )
    }

    companion object {
        /** Intent extra / saved-state key carrying the group id (same key the group settings page uses). */
        const val KEY_GROUP_ID = "groupId"
    }
}
