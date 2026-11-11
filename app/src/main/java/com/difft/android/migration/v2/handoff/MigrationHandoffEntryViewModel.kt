package com.difft.android.migration.v2.handoff

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.difft.android.base.log.lumberjack.L
import com.difft.android.migration.v2.transfer.MigrationTransferService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject









@HiltViewModel
class MigrationHandoffEntryViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val gate: MigrationHandoffGate,
    private val statusClient: MigrationStatusClient,
) : ViewModel() {

    private val _state = MutableStateFlow<MigrationHandoffEntryState>(MigrationHandoffEntryState.Loading)
    val state: StateFlow<MigrationHandoffEntryState> = _state.asStateFlow()

    private val _effects = Channel<MigrationHandoffEntryEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    
    private var probed = false

    fun onIntent(intent: MigrationHandoffEntryIntent) {
        when (intent) {
            is MigrationHandoffEntryIntent.Start -> probe(intent.autoStart)
            MigrationHandoffEntryIntent.ChooseHandoff -> emit(MigrationHandoffEntryEffect.StartReceive)
            MigrationHandoffEntryIntent.ChooseNormalLogin -> leavePath(MigrationHandoffEntryEffect.OpenLogin)
            MigrationHandoffEntryIntent.Back -> leavePath(MigrationHandoffEntryEffect.Finish)
        }
    }

    private fun probe(autoStart: Boolean) {
        if (probed) return
        probed = true
        viewModelScope.launch {
            val next = classify()
            L.i { "[MigrationV2][Handoff] entry state=${next::class.simpleName} autoStart=$autoStart" }
            _state.update { next }
            
            
            if (autoStart && next is MigrationHandoffEntryState.Offer) {
                emit(MigrationHandoffEntryEffect.StartReceive)
            }
        }
    }

    




    private suspend fun classify(): MigrationHandoffEntryState {
        if (!gate.canReceive()) {
            return MigrationHandoffEntryState.Blocked(HandoffEntryBlockReason.TARGET_NOT_EMPTY)
        }
        return when (val reply = statusClient.queryStatus()) {
            MigrationStatusClient.PeerStatusReply.TooOld -> MigrationHandoffEntryState.PeerTooOld
            MigrationStatusClient.PeerStatusReply.Unavailable ->
                MigrationHandoffEntryState.Blocked(HandoffEntryBlockReason.PEER_NO_ACCOUNT)
            is MigrationStatusClient.PeerStatusReply.Ok ->
                if (reply.state == HandoffState.READY) {
                    MigrationHandoffEntryState.Offer
                } else {
                    MigrationHandoffEntryState.Blocked(HandoffEntryBlockReason.PEER_NO_ACCOUNT)
                }
        }
    }

    
    private fun leavePath(effect: MigrationHandoffEntryEffect) {
        MigrationTransferService.cancel(appContext)
        emit(effect)
    }

    private fun emit(effect: MigrationHandoffEntryEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
