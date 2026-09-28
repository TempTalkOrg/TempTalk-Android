package com.difft.android.migration.v2.transfer

import com.difft.android.p2p.engine.model.StatusResponse
import com.difft.android.p2p.engine.model.TransferStages
import com.difft.android.p2p.engine.model.TransferTerminal
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sender's verdict wait, on a virtual clock: only `progressRevision` and `terminal` decide; a
 * stall is reported and slows the polling but never ends the wait; the total limit ends it as
 * unknown; a receiver that does not know the session ends it as unknown at once; and an
 * unreachable receiver is treated as one that made no progress.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VerdictWatchTest {

    private fun TestScope.watch(stallMs: Long = 60_000, totalMs: Long = 900_000, pollMs: Long = 10_000) =
        VerdictWatch(stallMs = stallMs, totalMs = totalMs, pollMs = pollMs, clock = { testScheduler.currentTime })

    private fun status(revision: Long, terminal: String? = null) =
        StatusResponse(stage = TransferStages.IMPORTING, records = 1, attachmentBytes = 0, progressRevision = revision, terminal = terminal, updatedAt = 0)

    @Test
    fun `a receiver that keeps moving is never a stall and a terminal ends the wait`() = runTest {
        var revision = 0L
        var polls = 0
        val stalls = mutableListOf<Boolean>()
        val verdict = watch().run(
            bodySentAt = 0,
            status = {
                polls++
                revision++
                if (polls == 5) status(revision, TransferTerminal.SUCCEEDED) else status(revision)
            },
            onStall = { stalls += it },
        )
        assertEquals(Verdict.Succeeded, verdict)
        assertEquals(5, polls)
        assertTrue(stalls.isEmpty())
        assertEquals(50_000L, testScheduler.currentTime)
    }

    @Test
    fun `no revision movement for the stall threshold is reported once, polling backs off, and resumes on movement`() = runTest {
        var revision = 1L
        val pollTimes = mutableListOf<Long>()
        val stalls = mutableListOf<Boolean>()
        val verdict = watch().run(
            bodySentAt = 0,
            status = {
                pollTimes += testScheduler.currentTime
                // Frozen until t=150s, then moving again, then done.
                if (testScheduler.currentTime >= 150_000) revision++
                if (revision >= 4) status(revision, TransferTerminal.SUCCEEDED) else status(revision)
            },
            onStall = { stalls += it },
        )
        assertEquals(Verdict.Succeeded, verdict)
        // Stalled once at the first poll on or after 60s of no movement, resumed once.
        assertEquals(listOf(true, false), stalls)
        // Steady 10s polls until the stall; then 20s, 30s, 30s … never past half the threshold.
        val gaps = pollTimes.zipWithNext { a, b -> b - a }
        assertTrue("gaps=$gaps", gaps.all { it <= 30_000 })
        assertTrue("gaps=$gaps", gaps.contains(20_000L) && gaps.contains(30_000L))
    }

    @Test
    fun `the total limit ends the wait as unknown without a cancel`() = runTest {
        var polls = 0
        val verdict = watch(totalMs = 120_000).run(bodySentAt = 0, status = { polls++; status(1) })
        assertEquals(Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT), verdict)
        assertEquals(120_000L, testScheduler.currentTime)
        assertTrue(polls > 1)
    }

    @Test
    fun `the total limit is measured from the body-sent time, not from the first poll`() = runTest {
        // Body sent long ago (a process that was suspended): the very first poll is already past the limit.
        val verdict = watch(totalMs = 120_000).run(bodySentAt = -200_000, status = { status(1) })
        assertEquals(Verdict.Unknown(Verdict.Unknown.Reason.TIMED_OUT), verdict)
        assertEquals(10_000L, testScheduler.currentTime)
    }

    @Test
    fun `a 404 ends the wait as session unknown at once, and a failed terminal is reported as such`() = runTest {
        assertEquals(
            Verdict.Unknown(Verdict.Unknown.Reason.SESSION_UNKNOWN),
            watch().run(bodySentAt = 0, status = { null }),
        )
        assertEquals(
            Verdict.Ended(TransferTerminal.FAILED),
            watch().run(bodySentAt = 0, status = { status(3, TransferTerminal.FAILED) }),
        )
    }

    @Test
    fun `an unreachable receiver counts as no progress and is asked again`() = runTest {
        var polls = 0
        val stalls = mutableListOf<Boolean>()
        val verdict = watch(totalMs = 200_000).run(
            bodySentAt = 0,
            status = {
                polls++
                if (polls < 4) throw IOException("connect refused") else status(1, TransferTerminal.CANCELLED)
            },
            onStall = { stalls += it },
        )
        assertEquals(Verdict.Ended(TransferTerminal.CANCELLED), verdict)
        assertEquals(4, polls)
        assertTrue(stalls.isEmpty()) // 40s of silence is under the threshold
    }
}
