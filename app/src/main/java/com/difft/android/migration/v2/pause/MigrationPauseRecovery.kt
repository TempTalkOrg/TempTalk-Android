package com.difft.android.migration.v2.pause

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.utils.appScope
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton








@Singleton
class MigrationPauseRecovery @Inject constructor(
    private val pause: MigrationMessagePause,
    private val loginScope: MigrationPauseLoginScope,
    private val settlement: SenderVerdictSettlement,
    private val pendingStore: PendingVerdictStore,
    private val recheck: MigrationVerdictRecheck,
) {
    
    internal var probeVerdict: suspend (SenderSessionRef) -> Verdict = recheck::probe

    fun start(scope: CoroutineScope = appScope) {
        scope.launch {
            onColdStart()
            recheck.start(scope)
        }
    }

    suspend fun onColdStart() {
        
        pause.onChanged.first { it != MessagingPauseState.Loading }
        loginScope.discardIfOutOfScope("cold_start")
        val record = pause.record ?: return
        when (record.phase) {
            
            MigrationPausePhase.EXPORTING ->
                settlement.settle(record.owner, null, MigrationPauseOutcome.NOT_COMMITTED, SOURCE)

            MigrationPausePhase.COMMITTING -> {
                val pending = pendingStore.load()?.takeIf { it.session.sessionId == record.sessionId }
                val verdict = pending?.let { probeVerdict(it.session) }
                settlement.settle(
                    owner = record.owner,
                    sessionId = record.sessionId,
                    outcome = verdict?.let(SenderVerdictSettlement::outcomeOf) ?: MigrationPauseOutcome.UNKNOWN,
                    source = SOURCE,
                )
            }

            MigrationPausePhase.AWAITING_LOGIN,
            MigrationPausePhase.AWAITING_DECISION ->
                L.i { "[MigrationPause] event=recovery_keep phase=${record.phase.name.lowercase()} owner=${record.owner.take(8)}" }
        }
    }

    private companion object {
        const val SOURCE = "cold_start"
    }
}
