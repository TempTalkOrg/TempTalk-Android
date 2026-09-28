package com.difft.android.chat.setting.translate.mvi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.TranslateSettingsViewModelFactory
import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.R
import com.difft.android.chat.message.LocalTranslateMessageCreator
import com.difft.android.chat.setting.ConversationSettingsManager
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract.Effect
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract.Intent
import com.difft.android.chat.setting.translate.mvi.TranslateSettingsContract.State
import com.difft.android.chat.translate.ModelId
import com.difft.android.chat.translate.TranslateLang
import com.difft.android.chat.translate.model.DownloadTrigger
import com.difft.android.chat.translate.model.ModelState
import com.difft.android.chat.translate.model.TranslateModelStore
import com.difft.android.messageserialization.db.store.DBRoomStore
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import difft.android.messageserialization.For
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Conversation auto-translate settings.
 *
 * This screen is the only code path that writes `RoomModel.translateSetting`. A selection lands in
 * the room row first and is broadcast afterwards, so anything that reacts to the broadcast by
 * re-reading the row can never observe the old value.
 *
 * "Only writer" is about code paths, not instances: another instance of this same screen, opened on
 * the same conversation, writes the same row. That is why [applySelection] re-reads the row inside
 * the write instead of trusting [State.selected].
 */
@HiltViewModel(assistedFactory = TranslateSettingsViewModelFactory::class)
class TranslateSettingsViewModel @AssistedInject constructor(
    @Assisted private val conversation: For,
    private val dbRoomStore: DBRoomStore,
    private val conversationSettingsManager: ConversationSettingsManager,
    private val localTranslateMessageCreator: LocalTranslateMessageCreator,
    private val modelStore: TranslateModelStore,
) : ViewModel() {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _effect = Channel<Effect>(Channel.BUFFERED)
    val effect = _effect.receiveAsFlow()

    /**
     * The value of the write currently in flight, or null. [State.selected] only moves once the row
     * is written, so without this a tap during the write would pass the idempotency guard — for the
     * same value it would insert the system message twice, for a different one it would race a
     * second write against the first one's broadcast. Touched from the main dispatcher only.
     */
    private var pendingSelect: Int? = null

    init {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                runCatching { dbRoomStore.getTranslateSetting(conversation.id) }
                    .onFailure { L.w { "[Translate] read setting failed room=${conversation.id}: ${it.stackTraceToString()}" } }
                    .getOrDefault(0)
            }
            _state.update { it.copy(loaded = true, selected = stored) }
        }
        observeModels()
    }

    fun dispatch(intent: Intent) {
        when (intent) {
            is Intent.Select -> select(intent.value)
            Intent.RetryDownload -> retryDownload()
            Intent.CancelDownload -> cancelDownload()
        }
    }

    /**
     * Idempotent by contract: re-picking the current value writes nothing, broadcasts nothing and
     * inserts no system message. [State.loaded] is the second half of the same guard — before the
     * stored value is known, the default 0 would look like a real change.
     *
     * [pendingSelect] is the third, and it rejects EVERY tap while a write is in flight, whatever
     * its value: [State.selected] only moves after the write, so a tap for a different value would
     * pass the idempotency guard and run a second write concurrently with the first — two rows, two
     * broadcasts, two system messages, in whatever order IO happened to finish. Dropping it is
     * correct rather than merely simple, because [State.pending] dims the rows for exactly that
     * window: the user sees the screen is busy and the confirmed value is always the one on screen.
     */
    private fun select(value: Int) {
        val current = _state.value
        if (!current.loaded || pendingSelect != null || value == current.selected) return
        pendingSelect = value
        _state.update { it.copy(pending = true) }
        viewModelScope.launch {
            try {
                applySelection(value)
            } finally {
                pendingSelect = null
                _state.update { it.copy(pending = false) }
            }
        }
    }

    private suspend fun applySelection(value: Int) {
        val alreadyStored = try {
            withContext(Dispatchers.IO) {
                // Read before write: the row is what says whether this is a real change. Another
                // instance of this screen, opened on the same conversation, may already have stored
                // the same value, and the system message must be inserted exactly once.
                val stored = dbRoomStore.getTranslateSetting(conversation.id)
                dbRoomStore.updateTranslateSetting(conversation.id, value)
                stored == value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            L.e { "[Translate] setting write failed room=${conversation.id} value=$value: ${e.stackTraceToString()}" }
            _effect.trySend(Effect.ShowToast(R.string.operation_failed))
            return
        }
        _state.update { it.copy(selected = value) }
        conversationSettingsManager.emitTranslateSettingUpdate(conversation.id, value)
        if (!alreadyStored) {
            localTranslateMessageCreator.createTranslateSettingChangedMessage(conversation, value)
        }
        TranslateLang.fromSetting(value)?.let {
            modelStore.ensureDownloaded(it, DownloadTrigger.SETTINGS_SELECT)
        }
        L.i { "[Translate] setting changed room=${conversation.id} value=$value notice=${!alreadyStored}" }
    }

    private fun retryDownload() {
        TranslateLang.fromSetting(_state.value.selected)?.let { modelStore.retryNow(it) }
    }

    private fun cancelDownload() {
        _state.value.models
            .filter { it.phase == State.Phase.DOWNLOADING }
            .forEach { modelStore.cancel(ModelId(it.modelCode)) }
    }

    /**
     * One status row per model the selected target needs. The selection half is de-duplicated, so a
     * model update writing `models` back into [_state] cannot re-trigger this collector.
     */
    private fun observeModels() {
        combine(
            _state.map { it.selected }.distinctUntilChanged(),
            modelStore.state,
        ) { selected, models ->
            val target = TranslateLang.fromSetting(selected)
            if (target == null) {
                emptyList()
            } else {
                modelStore.requiredModels(target).map { id -> models[id].toRow(id) }
            }
        }
            .onEach { rows -> _state.update { it.copy(models = rows) } }
            .launchIn(viewModelScope)
    }

    private fun ModelState?.toRow(id: ModelId): State.ModelRow = when (this) {
        null, ModelState.NotStarted -> State.ModelRow(id.code, State.Phase.IDLE)
        ModelState.Ready -> State.ModelRow(id.code, State.Phase.READY)
        is ModelState.Downloading -> State.ModelRow(
            modelCode = id.code,
            phase = State.Phase.DOWNLOADING,
            bytesSoFar = bytesSoFar,
            totalBytes = totalBytes,
            stuck = stuck,
        )

        is ModelState.Failed -> State.ModelRow(id.code, State.Phase.FAILED, reason = reason)
    }
}
