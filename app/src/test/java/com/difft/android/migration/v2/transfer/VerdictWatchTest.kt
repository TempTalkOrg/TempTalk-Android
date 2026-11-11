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
                
                if (testScheduler.currentTime >= 150_000) revision++
                if (revision >= 4) status(revision, TransferTerminal.SUCCEEDED) else status(revision)
            },
            onStall = { stalls += it },
        )
        assertEquals(Verdict.Succeeded, verdict)
        
        assertEquals(listOf(true, false), stalls)
        
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
    fun `only succeeded, failed and cancelled are definite, any other terminal is unknown`() = runTest {
        assertEquals(Verdict.Succeeded, VerdictWatch.verdictOf(TransferTerminal.SUCCEEDED))
        assertEquals(Verdict.Ended(TransferTerminal.FAILED), VerdictWatch.verdictOf(TransferTerminal.FAILED))
        assertEquals(Verdict.Ended(TransferTerminal.CANCELLED), VerdictWatch.verdictOf(TransferTerminal.CANCELLED))
        val unknown = Verdict.Unknown(Verdict.Unknown.Reason.UNRECOGNIZED_TERMINAL)
        for (terminal in listOf("aborted", "FAILED", "canceled", "", "rolled_back")) {
            assertEquals("terminal=$terminal", unknown, VerdictWatch.verdictOf(terminal))
        }
        assertEquals(unknown, watch().run(bodySentAt = 0, status = { status(3, "partially-imported") }))
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
        assertTrue(stalls.isEmpty()) 
    }
}
