package com.difft.android.migration.v2.transfer

import com.difft.android.migration.model.MigrationError
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A receive that stops short is reported the way iOS reports it: a sender that said it left is the
 * source going away, a link that merely dropped is a connection problem — and because the sender's
 * cancel notice follows its hang-up on the wire, the drop must wait a moment for that notice.
 */
class ReceiverFailureArbiterTest {

    private val verdicts = mutableListOf<String>()

    @Test
    fun `a dropped link followed by the peer's cancel is the peer leaving, reported once`() = runTest {
        val arbiter = ReceiverFailureArbiter(backgroundScope, verdicts::add, cancelGraceMs = 1_500)

        arbiter.onFailure(ReceiverFailureArbiter.DROPPED_LINK)
        advanceTimeBy(200)
        arbiter.onPeerCancelled()
        advanceTimeBy(5_000)

        assertEquals(listOf(ReceiverFailureArbiter.PEER_LEFT), verdicts)
    }

    @Test
    fun `a dropped link with no cancel notice is a connection failure after the grace`() = runTest {
        val arbiter = ReceiverFailureArbiter(backgroundScope, verdicts::add, cancelGraceMs = 1_500)

        arbiter.onFailure(ReceiverFailureArbiter.DROPPED_LINK)
        // The import's verdict for the same drop arrives while the first is pending: one report, not two.
        arbiter.onFailure(ReceiverFailureArbiter.DROPPED_LINK)
        advanceTimeBy(1_000)
        assertEquals(emptyList<String>(), verdicts)

        advanceTimeBy(1_000)
        assertEquals(listOf(ReceiverFailureArbiter.DROPPED_LINK), verdicts)
    }

    @Test
    fun `anything but a dropped link is reported at once`() = runTest {
        val arbiter = ReceiverFailureArbiter(backgroundScope, verdicts::add)

        arbiter.onFailure("failed: ${MigrationError.HmacMismatch.code}")

        assertEquals(listOf("failed: ${MigrationError.HmacMismatch.code}"), verdicts)
    }

    @Test
    fun `a sender that prepares and never uploads is a lost link, one that uploads is not`() = runTest {
        val arbiter = ReceiverFailureArbiter(backgroundScope, verdicts::add, uploadStartTimeoutMs = 30_000)

        arbiter.onPrepared()
        advanceTimeBy(29_000)
        assertEquals(emptyList<String>(), verdicts)
        advanceTimeBy(2_000)
        assertEquals(listOf(ReceiverFailureArbiter.DROPPED_LINK), verdicts)

        verdicts.clear()
        arbiter.onPrepared()
        advanceTimeBy(10_000)
        arbiter.onUploadStarted()
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), verdicts)
    }
}
