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

/**
 * Decides what the first-launch migration page may offer, and owns the rule that every way off this
 * path stops the receiver first.
 *
 * The receiver is a foreground service listening on loopback, and its duplicate-start guard only
 * prevents a second transfer. A receiver left alive while the user signs in normally would stay
 * ready to adopt a session into an install that is no longer empty.
 */
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

    /** Probe once per Activity instance; a configuration change must not re-run the binder call. */
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
            // The user already chose this in the old app; asking again would confirm the same
            // decision twice.
            if (autoStart && next is MigrationHandoffEntryState.Offer) {
                emit(MigrationHandoffEntryEffect.StartReceive)
            }
        }
    }

    /**
     * Both halves have to agree before anything is offered: this install must be empty, and the peer
     * must hold a complete session. Every unclear answer — unreachable peer, unusable reply — reads
     * as "do not offer".
     */
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

    /** Stop the receiver before navigating away, never after; the order is the point. */
    private fun leavePath(effect: MigrationHandoffEntryEffect) {
        MigrationTransferService.cancel(appContext)
        emit(effect)
    }

    private fun emit(effect: MigrationHandoffEntryEffect) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
