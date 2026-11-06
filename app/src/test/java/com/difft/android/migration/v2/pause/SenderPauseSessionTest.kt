package com.difft.android.migration.v2.pause

import com.difft.android.base.migration.pause.AdmissionKind
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.chat.messages.RetryQueueResidual
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.p2p.engine.model.ReceiverCaps
import com.difft.android.test.fakes.TestMessagingPause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [SenderPauseSession]: begin, quiescence, drain and the commit marker (T8, T15, T18, T22, #1382). */
class SenderPauseSessionTest {

    private val owner = "33333333-aaaa-bbbb-cccc-000000000003"
    private val messaging = TestMessagingPause().open()
    private val events = mutableListOf<String>()
    private var login: MigrationPauseLoginScope.Scope? = MigrationPauseLoginScope.Scope("scope", "cred")
    private val pendingStore = PendingVerdictStore(messaging.store)
    private val statusRef = SenderSessionRef("10.0.0.2", 53317, "fp", "session-1", "token", listOf(ReceiverCaps.STATUS_V1))

    private fun receiptSession(): String? = runBlocking { pendingStore.load()?.session?.sessionId }

    private fun session(
        timeoutMs: Long = 5_000L,
        afterDrain: suspend () -> Unit = {},
        persistReceipt: Boolean = true,
        residual: RetryQueueResidual = RetryQueueResidual(failed = 0, pending = 3),
    ) = SenderPauseSession(
        owner = owner,
        pause = messaging.pause,
        gate = messaging.gate,
        scope = { login },
        drainRetryQueues = { drainOwner ->
            // The drain runs as the owner, after every earlier ticket closed, with nobody else admitted.
            assertEquals(owner, drainOwner)
            assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
            assertNull(messaging.gate.tryAdmit(AdmissionKind.RECEIVE, "late"))
            events += "drain"
            afterDrain()
            residual
        },
        receiptEdit = if (persistReceipt) pendingStore::saveEdit else null,
        quiescenceTimeout = timeoutMs.milliseconds,
    )

    /**
     * What an owner drain does when a replayed envelope is queued for a deferred consumer (a
     * receipt, a group or contact notify): retains the replay's ticket, hands it over and closes
     * its own; the consumer closes the retained one once it has persisted.
     */
    private fun replayIntoDeferredConsumer(): MessagingTicket {
        val replay = assertNotNull(messaging.gate.tryAdmitAsOwner(owner, AdmissionKind.RECEIVE, "pending-drain"))
        val handedOver = assertNotNull(replay.retain("receipt"))
        replay.close()
        return handedOver
    }

    @Test
    fun `enter pauses, waits for admitted work, then drains`() = runBlocking<Unit> {
        val inFlight = assertNotNull(messaging.gate.tryAdmit(AdmissionKind.SEND, "job"))
        val entered = async(Dispatchers.Default) { session().enter() }

        withTimeout(5.seconds) { while (messaging.gate.state == MessagingPauseState.Open) delay(5) }
        delay(100)
        assertEquals(emptyList<String>(), events, "no drain while an admitted send is still running")
        events += "send_done"
        inFlight.close()
        entered.await()

        assertEquals(listOf("send_done", "drain"), events)
        assertEquals(owner, messaging.pause.record?.owner)
    }

    @Test
    fun `a quiescence timeout fails the export before the drain`() = runBlocking<Unit> {
        messaging.gate.tryAdmit(AdmissionKind.SEND, "stuck-job")

        val e = assertFailsWith<MigrationPauseException> { session(timeoutMs = 50).enter() }

        assertEquals(SenderPauseSession.REASON_QUIESCENCE_TIMEOUT, e.message)
        assertEquals(emptyList<String>(), events)
        // The pause is the export's own and still exporting, so settling the failure clears it.
        assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
    }

    @Test
    fun `enter waits for the work an owner drain handed to a deferred consumer`() = runBlocking<Unit> {
        var handedOver: MessagingTicket? = null
        val entered = async(Dispatchers.Default) {
            session(afterDrain = { handedOver = replayIntoDeferredConsumer() }).enter()
            events += "entered"
        }

        withTimeout(5.seconds) { while ("drain" !in events) delay(5) }
        delay(100)
        assertEquals(listOf("drain"), events, "no snapshot while a replayed receipt is still being applied")
        events += "receipt_applied"
        assertNotNull(handedOver).close()
        entered.await()

        assertEquals(listOf("drain", "receipt_applied", "entered"), events)
    }

    @Test
    fun `deferred drain work that never finishes fails the export within the one budget`() = runBlocking<Unit> {
        val e = assertFailsWith<MigrationPauseException> {
            session(timeoutMs = 100, afterDrain = { replayIntoDeferredConsumer() }).enter()
        }

        assertEquals(SenderPauseSession.REASON_QUIESCENCE_TIMEOUT, e.message)
        assertEquals(listOf("drain"), events)
        assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
    }

    @Test
    fun `failed rows left after the drain stop the export once the deferred work is done`() = runBlocking<Unit> {
        var handedOver: MessagingTicket? = null
        val entered = async(Dispatchers.Default) {
            runCatching {
                session(afterDrain = { handedOver = replayIntoDeferredConsumer() }, residual = RetryQueueResidual(failed = 1, pending = 0)).enter()
            }
        }

        withTimeout(5.seconds) { while ("drain" !in events) delay(5) }
        delay(100)
        assertFalse(entered.isCompleted, "the residual check runs after the second wait")
        assertNotNull(handedOver).close()
        val e = assertIs<MigrationPauseException>(entered.await().exceptionOrNull())

        assertEquals(SenderPauseSession.REASON_RESIDUAL, e.message)
        // Still this export's pause and still exporting: settling the failure clears it (nothing exported).
        assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
        val settled = SenderVerdictSettlement(messaging.pause, pendingStore)
            .settle(owner, null, MigrationPauseOutcome.NOT_COMMITTED, "test")
        assertEquals(SettleResult.APPLIED, settled)
        assertNull(messaging.pause.record)
        assertTrue(messaging.gate.isOpen)
    }

    @Test
    fun `an unknown failed-row count stops the export`() = runBlocking<Unit> {
        val e = assertFailsWith<MigrationPauseException> { session(residual = RetryQueueResidual(failed = null, pending = 0)).enter() }

        assertEquals(SenderPauseSession.REASON_RESIDUAL, e.message)
    }

    @Test
    fun `pending rows are not residual`() = runBlocking<Unit> {
        session(residual = RetryQueueResidual(failed = 0, pending = 5)).enter()

        assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
    }

    @Test
    fun `an export cannot pause over another export's pause`() = runBlocking<Unit> {
        messaging.pauseMessaging("earlier-export")

        val e = assertFailsWith<MigrationPauseException> { session().enter() }

        assertEquals(SenderPauseSession.REASON_UNAVAILABLE, e.message)
        assertEquals("earlier-export", messaging.pause.record?.owner)
    }

    @Test
    fun `an export cannot start while an earlier one awaits a decision, and its receipt stays`() = runBlocking<Unit> {
        messaging.pauseMessaging("earlier-export")
        messaging.pause.committing("earlier-export", "earlier-session", pendingStore.saveEdit(PendingVerdict(statusRef.copy(sessionId = "earlier-session"), 1L)))
        messaging.pause.finish("earlier-export", null, MigrationPauseOutcome.UNKNOWN)

        val e = assertFailsWith<MigrationPauseException> { session().enter() }

        assertEquals(SenderPauseSession.REASON_UNAVAILABLE, e.message)
        assertEquals("earlier-export", messaging.pause.record?.owner)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, messaging.pause.record?.phase)
        assertEquals("earlier-session", runBlocking { pendingStore.load(now = 2L)?.session?.sessionId })
    }

    @Test
    fun `an export cannot replace a confirmed import awaiting the old login`() = runBlocking<Unit> {
        messaging.pauseMessaging("earlier-export")
        messaging.pause.committing("earlier-export", "earlier-session")
        messaging.pause.finish("earlier-export", null, MigrationPauseOutcome.SUCCESS)

        val e = assertFailsWith<MigrationPauseException> { session().enter() }

        assertEquals(SenderPauseSession.REASON_UNAVAILABLE, e.message)
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, messaging.pause.record?.phase)
    }

    @Test
    fun `an export with no login pauses nothing`() = runBlocking<Unit> {
        login = null

        assertFailsWith<MigrationPauseException> { session().enter() }

        assertEquals(MessagingPauseState.Open, messaging.gate.state)
    }

    @Test
    fun `the commit marker records the receiver session and its receipt in one write`() = runBlocking<Unit> {
        val s = session()
        s.enter()
        s.onSessionPrepared(statusRef)
        val writesBefore = messaging.store.writeCount.get()

        s.beforeFinalize()

        val record = assertNotNull(messaging.pause.record)
        assertEquals(MigrationPausePhase.COMMITTING, record.phase)
        assertEquals("session-1", record.sessionId)
        assertEquals("session-1", receiptSession())
        assertEquals(writesBefore + 1, messaging.store.writeCount.get(), "marker and receipt land together")
    }

    @Test
    fun `no receipt for a receiver without status or a handoff`() = runBlocking<Unit> {
        val noStatus = session()
        noStatus.enter()
        noStatus.onSessionPrepared(statusRef.copy(caps = emptyList()))
        noStatus.beforeFinalize()
        assertNull(receiptSession())
        messaging.resumeMessaging()

        val handoff = session(persistReceipt = false)
        handoff.enter()
        handoff.onSessionPrepared(statusRef)
        handoff.beforeFinalize()
        assertNull(receiptSession())
        assertEquals(MigrationPausePhase.COMMITTING, messaging.pause.record?.phase)
    }

    @Test
    fun `a commit marker that did not land throws and writes no receipt`() = runBlocking<Unit> {
        val s = session()
        s.enter()
        s.onSessionPrepared(statusRef)
        // The pause went away meanwhile (a logout): the marker cannot be written for this export.
        messaging.pause.clear(owner = null, reason = "test")

        assertFailsWith<MigrationPauseException> { s.beforeFinalize() }
        assertNull(receiptSession())
    }

    @Test
    fun `the end-marker flag is raised only by the hook that follows the marker`() = runBlocking<Unit> {
        val s = session()
        s.enter()
        assertFalse(s.endMarkerWritten)
        s.onEndMarker()
        assertTrue(s.endMarkerWritten)
    }

    @Test
    fun `no commit marker before the receiver opened a session`() = runBlocking<Unit> {
        val s = session()
        s.enter()

        assertFailsWith<MigrationPauseException> { s.beforeFinalize() }
        assertEquals(MigrationPausePhase.EXPORTING, messaging.pause.record?.phase)
        assertFalse(messaging.gate.isOpen)
    }

    @Test
    fun `the receiver's answer to the pipeline's cancel is kept`() {
        val s = session()
        s.onAbortAnswer("stopped")
        assertEquals("stopped", s.abortAnswer)
    }
}
