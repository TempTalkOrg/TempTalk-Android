package com.difft.android.migration.v2.transfer.send

import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.migration.v2.pause.SenderVerdictSettlement
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [SendScreenVerdicts]: "Start over" on the unconfirmed page must not delete the receipt a pause
 * awaiting the receiver's decision still needs (#1382, design §2.5 "Start over → no change").
 */
class SendScreenVerdictsTest {

    private val messaging = TestMessagingPause().open()
    private val store = PendingVerdictStore(messaging.store)
    private val verdicts = SendScreenVerdicts(store, SenderVerdictSettlement(messaging.pause, store))
    private val session = SenderSessionRef("10.0.0.2", 53317, "fp", SESSION, "token", listOf("status-v1"))

    private fun awaitingDecision() = runBlocking {
        messaging.pauseMessaging(OWNER)
        messaging.pause.committing(OWNER, SESSION)
        store.save(PendingVerdict(session, System.currentTimeMillis()))
        verdicts.settle(session, Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE))
        assertEquals(MigrationPausePhase.AWAITING_DECISION, messaging.pause.record?.phase)
    }

    @Test
    fun `start over keeps the receipt while the pause awaits a decision`() = runBlocking<Unit> {
        awaitingDecision()

        verdicts.clear()

        assertNotNull(store.load(), "the re-check and cold start still need the receipt")
        // The receiver later answers: the receipt is still there to settle the pause with.
        verdicts.settle(session, Verdict.Ended("failed"))
        assertNull(messaging.pause.record)
        assertNull(store.load())
    }

    @Test
    fun `start over clears the receipt once no decision is awaited`() = runBlocking<Unit> {
        store.save(PendingVerdict(session, System.currentTimeMillis()))

        verdicts.clear()

        assertNull(store.load())
    }

    @Test
    fun `a definite re-checked verdict settles the pause before the receipt goes`() = runBlocking<Unit> {
        awaitingDecision()

        verdicts.settle(session, Verdict.Succeeded)

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, messaging.pause.record?.phase)
        assertNull(store.load())
    }

    @Test
    fun `a verdict the pause does not take is not shown as final and keeps the receipt`() = runBlocking<Unit> {
        awaitingDecision()
        verdicts.settle(session, Verdict.Succeeded)
        store.save(PendingVerdict(session, System.currentTimeMillis()))

        // A confirmed import is final: a late "failed" for it is skipped.
        assertFalse(verdicts.settle(session, Verdict.Ended("failed")))

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, messaging.pause.record?.phase)
        assertNotNull(store.load())
    }

    private companion object {
        const val SESSION = "session-1382"
    }
}
