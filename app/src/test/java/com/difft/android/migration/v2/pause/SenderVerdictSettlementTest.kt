package com.difft.android.migration.v2.pause

import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.SenderVerdictTracker.CancelDecision
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.p2p.engine.model.CancelState
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [SenderVerdictSettlement]: the outcome table and the in-write checks of #1382 (A01, A06, A11). */
class SenderVerdictSettlementTest {

    private val messaging = TestMessagingPause().open()
    private val pending = PendingVerdictStore(messaging.store)
    private val settlement = SenderVerdictSettlement(messaging.pause, pending)

    private fun exporting() = messaging.pauseMessaging(OWNER)

    /** The commit marker with this export's receipt, in one write. */
    private fun committing(session: String = SESSION) = runBlocking<Unit> {
        exporting()
        messaging.pause.committing(OWNER, session, pending.saveEdit(receipt(session)))
    }

    private fun receipt(session: String) = PendingVerdict(SenderSessionRef("10.0.0.2", 53317, "fp", session, "token", listOf("status-v1")), 1_000L)

    private fun storedReceiptSession(): String? = runBlocking { pending.load(now = 2_000L)?.session?.sessionId }

    private fun rawReceipt(): String? = runBlocking { messaging.store.data.first()[AppStateKeys.MIGRATION_PENDING_VERDICT] }

    private fun phase(): MigrationPausePhase? = messaging.pause.record?.phase

    private fun settle(outcome: MigrationPauseOutcome, owner: String? = OWNER, sessionId: String? = SESSION) =
        runBlocking { settlement.settle(owner, sessionId, outcome, "test") }

    @Test
    fun `exporting and an unknown, failed or never-committed end is cleared`() {
        for (outcome in listOf(MigrationPauseOutcome.UNKNOWN, MigrationPauseOutcome.FAILED, MigrationPauseOutcome.NOT_COMMITTED)) {
            exporting()
            assertEquals(SettleResult.APPLIED, settle(outcome))
            assertEquals(MessagingPauseState.Open, messaging.gate.state)
        }
    }

    @Test
    fun `committing and the cancel answered stopped or rolled back is cleared with its receipt`() {
        committing()
        assertEquals(SettleResult.APPLIED, settle(SenderVerdictSettlement.outcomeOfCancel(null, CancelState.STOPPED)))
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(rawReceipt())

        committing()
        settle(SenderVerdictSettlement.outcomeOfCancel(CancelDecision.CANCELLED, null))
        assertEquals(MessagingPauseState.Open, messaging.gate.state)

        committing()
        settle(SenderVerdictSettlement.outcomeOfCancel(null, CancelState.ROLLED_BACK))
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
    }

    @Test
    fun `committing and any other cancel answer awaits a decision, paused, with its receipt`() {
        for (answer in listOf(CancelState.TOO_LATE, null, "later_state")) {
            committing()
            assertEquals(SettleResult.APPLIED, settle(SenderVerdictSettlement.outcomeOfCancel(null, answer)))
            assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
            assertIs<MessagingPauseState.Paused>(messaging.gate.state, "an unresolved transfer keeps messaging paused")
            assertEquals(SESSION, storedReceiptSession())
            messaging.resumeMessaging()
        }
    }

    @Test
    fun `sent awaits the old login and drops the receipt in the same write`() {
        committing()
        assertEquals(SettleResult.APPLIED, settle(MigrationPauseOutcome.SUCCESS))
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, phase())
        assertNull(rawReceipt())
    }

    @Test
    fun `an unknown verdict keeps the receipt`() {
        committing()
        settle(SenderVerdictSettlement.outcomeOf(Verdict.Unknown(Verdict.Unknown.Reason.UNRECOGNIZED_TERMINAL)))
        assertEquals(MigrationPausePhase.AWAITING_DECISION, phase())
        assertEquals(SESSION, storedReceiptSession())
    }

    @Test
    fun `a decision pending resolves on the re-check, by session`() {
        committing()
        settle(MigrationPauseOutcome.UNKNOWN)

        assertEquals(SettleResult.APPLIED, settle(SenderVerdictSettlement.outcomeOf(Verdict.Ended("failed")), owner = null))

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(rawReceipt())
    }

    @Test
    fun `a late success after a decision pending pauses for the old login`() {
        committing()
        settle(MigrationPauseOutcome.UNKNOWN)

        settle(SenderVerdictSettlement.outcomeOf(Verdict.Succeeded), owner = null)

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, phase())
        assertNull(rawReceipt())
    }

    @Test
    fun `a settlement whose record was cleared under it is skipped and cleans nothing`() {
        committing()
        // A logout or scope check removed the record between the caller's read and its write; a
        // newer receipt (another send's) is in place meanwhile.
        runBlocking {
            messaging.pause.clear(OWNER, "test")
            pending.save(receipt("next-session"))
        }

        assertEquals(SettleResult.SKIPPED, settle(MigrationPauseOutcome.FAILED))

        assertEquals("next-session", storedReceiptSession())
    }

    @Test
    fun `a conflicting verdict for a confirmed import is skipped and keeps the receipt`() {
        committing()
        settle(MigrationPauseOutcome.SUCCESS)
        runBlocking { pending.save(receipt(SESSION)) }

        assertEquals(SettleResult.SKIPPED, settle(MigrationPauseOutcome.FAILED))

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, phase())
        assertEquals(SESSION, storedReceiptSession())
    }

    @Test
    fun `a definite end deletes only its own session's receipt`() {
        committing()
        settle(MigrationPauseOutcome.UNKNOWN)
        runBlocking { pending.save(receipt("another-session")) }

        settle(MigrationPauseOutcome.FAILED)

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertEquals("another-session", storedReceiptSession(), "compare-and-delete keeps another session's receipt")
    }

    @Test
    fun `a send with no pause settles as such and drops its own receipt`() {
        runBlocking { pending.save(receipt("link-session")) }

        assertEquals(SettleResult.NO_PAUSE, settle(MigrationPauseOutcome.FAILED, owner = null, sessionId = "link-session"))

        assertNull(rawReceipt())
    }

    @Test
    fun `a session-named settlement while another export is paused is not that export's`() {
        committing()

        assertEquals(SettleResult.NO_PAUSE, settle(MigrationPauseOutcome.FAILED, owner = null, sessionId = "link-session"))

        assertEquals(MigrationPausePhase.COMMITTING, phase())
        assertEquals(SESSION, storedReceiptSession())
    }

    @Test
    fun `a write failure keeps the receipt and reports it`() {
        committing()
        val flaky = object : androidx.datastore.core.DataStore<Preferences> by messaging.store {
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
                throw java.io.IOException("disk")
        }
        val failing = SenderVerdictSettlement(
            com.difft.android.base.migration.pause.MigrationMessagePause(flaky, messaging.gate),
            pending,
        )

        val result = runBlocking { failing.settle(OWNER, SESSION, MigrationPauseOutcome.FAILED, "test") }

        assertEquals(SettleResult.WRITE_FAILED, result)
        assertEquals(SESSION, storedReceiptSession())
    }

    @Test
    fun `releasing a receipt keeps it while the pause waits on that session`() = runBlocking<Unit> {
        committing()
        settle(MigrationPauseOutcome.UNKNOWN)

        settlement.releasePending(SESSION)
        assertEquals(SESSION, storedReceiptSession())

        messaging.resumeMessaging()
        pending.save(receipt(SESSION))
        settlement.releasePending(SESSION)
        assertNull(rawReceipt())
    }

    @Test
    fun `another export's settlement changes nothing`() {
        committing()
        assertEquals(SettleResult.SKIPPED, settle(MigrationPauseOutcome.FAILED, owner = "other-export"))
        assertEquals(MigrationPausePhase.COMMITTING, phase())
        assertNotNull(rawReceipt())
    }

    @Test
    fun `a settlement started by a cancelled coroutine still commits`() = runBlocking<Unit> {
        committing()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                settlement.settle(OWNER, SESSION, MigrationPauseOutcome.FAILED, "test")
            }
        }
        job.cancelAndJoin()

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertNull(rawReceipt())
    }

    @Test
    fun `only stopped and rolled back are a definite cancel`() {
        assertEquals(MigrationPauseOutcome.FAILED, SenderVerdictSettlement.outcomeOfCancel(null, CancelState.STOPPED))
        assertEquals(MigrationPauseOutcome.FAILED, SenderVerdictSettlement.outcomeOfCancel(null, CancelState.ROLLED_BACK))
        for (answer in listOf(CancelState.TOO_LATE, null, "", "cancelled", "failed")) {
            assertEquals(MigrationPauseOutcome.UNKNOWN, SenderVerdictSettlement.outcomeOfCancel(null, answer))
        }
        assertEquals(MigrationPauseOutcome.UNKNOWN, SenderVerdictSettlement.outcomeOfCancel(CancelDecision.KEEP_WAITING, CancelState.STOPPED))
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
