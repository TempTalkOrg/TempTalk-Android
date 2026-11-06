package com.difft.android.migration.v2.pause

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.test.fakes.TestMessagingPause
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [MigrationVerdictRecheck]: the app-level `/status` re-check while a pause awaits a decision (#1382 A07). */
@OptIn(ExperimentalCoroutinesApi::class)
class MigrationVerdictRecheckTest {

    private val messaging = TestMessagingPause().open()
    private val pendingStore = PendingVerdictStore(messaging.store)
    private val settlement = SenderVerdictSettlement(messaging.pause, pendingStore)
    private val recheck = MigrationVerdictRecheck(messaging.pause, pendingStore, settlement)
    private val foreground = MutableStateFlow(true)
    private val session = SenderSessionRef("10.0.0.2", 53317, "fp", SESSION, "token", listOf("status-v1"))
    private val asked = AtomicInteger()

    private suspend fun undecided(withReceipt: Boolean = true) {
        messaging.pauseMessaging(OWNER)
        val receipt = if (withReceipt) pendingStore.saveEdit(PendingVerdict(session, System.currentTimeMillis())) else null
        messaging.pause.committing(OWNER, SESSION, receipt)
        messaging.pause.finish(OWNER, null, MigrationPauseOutcome.UNKNOWN)
    }

    private fun TestScope.start(answer: (Int) -> Verdict) {
        recheck.probeVerdict = { answer(asked.incrementAndGet()) }
        backgroundScope.launch { recheck.run(foreground, intervalMs = 10_000L) }
    }

    @Test
    fun `asks every interval in the foreground until the receiver says failed`() = runTest {
        undecided()
        start { n -> if (n < 3) Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE) else Verdict.Ended("failed") }

        runCurrent()
        advanceTimeBy(25_000L)

        assertEquals(3, asked.get())
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
        assertEquals(null, pendingStore.load())
        advanceTimeBy(60_000L)
        assertEquals(3, asked.get(), "nothing is asked once the pause is settled")
    }

    @Test
    fun `a succeeded answer moves the pause to await the old login`() = runTest {
        undecided()
        start { Verdict.Succeeded }

        runCurrent()

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, messaging.pause.record?.phase)
    }

    @Test
    fun `an unrecognized terminal changes nothing`() = runTest {
        undecided()
        start { com.difft.android.migration.v2.transfer.VerdictWatch.verdictOf("partially-imported") }

        runCurrent()
        advanceTimeBy(10_001L)

        assertEquals(2, asked.get())
        assertEquals(MigrationPausePhase.AWAITING_DECISION, messaging.pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(messaging.gate.state)
    }

    @Test
    fun `nothing is asked in the background, committing or without a receipt`() = runTest {
        foreground.value = false
        undecided()
        start { Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE) }
        runCurrent()
        advanceTimeBy(30_000L)
        assertEquals(0, asked.get())

        foreground.value = true
        runCurrent()
        assertEquals(1, asked.get(), "asked at once on returning to the foreground")

        messaging.resumeMessaging()
        undecided(withReceipt = false)
        runCurrent()
        advanceTimeBy(30_000L)
        assertEquals(1, asked.get(), "no receipt, nothing to ask with; the record stays")
        assertEquals(MigrationPausePhase.AWAITING_DECISION, messaging.pause.record?.phase)
    }

    @Test
    fun `a failed receipt read keeps re-checking instead of going idle`() = runTest {
        val failReads = AtomicInteger(1)
        val flaky = object : DataStore<Preferences> by messaging.store {
            override val data: Flow<Preferences>
                get() = if (failReads.getAndDecrement() > 0) flow { throw IOException("disk") } else messaging.store.data
        }
        val flakyRecheck = MigrationVerdictRecheck(messaging.pause, PendingVerdictStore(flaky), settlement)
        undecided()
        flakyRecheck.probeVerdict = {
            asked.incrementAndGet()
            Verdict.Ended("failed")
        }
        backgroundScope.launch { flakyRecheck.run(foreground, intervalMs = 10_000L) }

        runCurrent()
        assertEquals(0, asked.get(), "nothing to ask with while the receipt cannot be read")
        assertEquals(MigrationPausePhase.AWAITING_DECISION, messaging.pause.record?.phase)

        advanceTimeBy(10_001L)

        assertEquals(1, asked.get(), "the next beat reads the receipt and asks")
        assertEquals(MessagingPauseState.Open, messaging.gate.state)
    }

    @Test
    fun `the page's question and the loop's never run together`() = runTest {
        val inFlight = AtomicInteger()
        var maxInFlight = 0
        val release = CompletableDeferred<Unit>()
        recheck.probeVerdict = {
            maxInFlight = maxOf(maxInFlight, inFlight.incrementAndGet())
            release.await()
            inFlight.decrementAndGet()
            Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE)
        }
        val first = async { recheck.probe(session) }
        val second = async { recheck.probe(session) }
        runCurrent()
        release.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, maxInFlight)
    }

    private companion object {
        const val SESSION = "session-7"
    }
}
