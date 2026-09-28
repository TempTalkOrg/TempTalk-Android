package com.difft.android.dualpane

import org.junit.Assert.assertEquals
import org.junit.Test

class PaneRebuildBreakerTest {

    private data class Inputs(val name: String, val gate: Boolean, val tree: Boolean, val chromeStale: Boolean)

    private companion object {
        val SHRUNK = Inputs("shrunk below the floor", gate = false, tree = true, chromeStale = false)
        val GREW = Inputs("grew past the floor", gate = true, tree = false, chromeStale = false)
    }

    private fun PaneRebuildBreaker.rebuild(inputs: Inputs): PaneRebuildVerdict =
        onRebuild(gate = inputs.gate, tree = inputs.tree, chromeStale = inputs.chromeStale)

    @Test
    fun `one unchanged disagreement - three proceeds, one trip, then silence`() {
        val expected = listOf(
            PaneRebuildVerdict.PROCEED,
            PaneRebuildVerdict.PROCEED,
            PaneRebuildVerdict.PROCEED,
            PaneRebuildVerdict.TRIP,
            PaneRebuildVerdict.SUPPRESSED,
            PaneRebuildVerdict.SUPPRESSED,
        )
        val breaker = PaneRebuildBreaker()
        val actual = (1..expected.size).map { breaker.rebuild(SHRUNK) }
        assertEquals(
            "an unchanged disagreement must be rebuilt 3 times, then frozen: any extra PROCEED is an" +
                " unbounded relaunch loop, an earlier TRIP freezes an ordinary drag across the floor",
            expected,
            actual,
        )
        assertEquals("TRIP gates an L.e line, so it must fire once per open breaker", 1, actual.count { it == PaneRebuildVerdict.TRIP })
    }

    @Test
    fun `a changed disagreement resets the count instead of inheriting it`() {
        val breaker = PaneRebuildBreaker()
        repeat(3) { breaker.rebuild(SHRUNK) }
        assertEquals("a NEW disagreement must proceed, not inherit the spent budget", PaneRebuildVerdict.PROCEED, breaker.rebuild(GREW))
        assertEquals("call 2 of the new disagreement", PaneRebuildVerdict.PROCEED, breaker.rebuild(GREW))
        assertEquals("call 3 of the new disagreement", PaneRebuildVerdict.PROCEED, breaker.rebuild(GREW))
        assertEquals("the new disagreement is bounded by the same 3", PaneRebuildVerdict.TRIP, breaker.rebuild(GREW))
    }

    @Test
    fun `an alternating disagreement is bounded by the total ceiling`() {
        // 12 = four full per-disagreement budgets since the last agreement (shipped default).
        val expected = List(12) { PaneRebuildVerdict.PROCEED } +
            listOf(PaneRebuildVerdict.TRIP, PaneRebuildVerdict.SUPPRESSED, PaneRebuildVerdict.SUPPRESSED)
        val breaker = PaneRebuildBreaker()
        val actual = (1..expected.size).map { call ->
            val shrinking = call % 2 == 1
            breaker.onRebuild(gate = !shrinking, tree = shrinking, chromeStale = false)
        }
        assertEquals(
            "an alternating gate/tree disagreement with no agreement between rebuilds must be bounded;" +
                " all-PROCEED means a signature change re-arms the breaker on every call",
            expected,
            actual,
        )
    }

    @Test
    fun `a window dragged across the floor alternates through agreement and never trips`() {
        val breaker = PaneRebuildBreaker()
        repeat(20) { step ->
            val shrinking = step % 2 == 0
            assertEquals(
                "drag step $step must proceed: the rebuild before it reached agreement, so this is a fresh crossing",
                PaneRebuildVerdict.PROCEED,
                breaker.onRebuild(gate = !shrinking, tree = shrinking, chromeStale = false),
            )
            breaker.onAgreement()
        }
    }

    @Test
    fun `onAgreement re-arms a tripped breaker`() {
        val breaker = PaneRebuildBreaker()
        repeat(4) { breaker.rebuild(SHRUNK) }
        assertEquals("precondition: the breaker is open", PaneRebuildVerdict.SUPPRESSED, breaker.rebuild(SHRUNK))
        breaker.onAgreement()
        assertEquals("an agreement must give the next disagreement a fresh budget", PaneRebuildVerdict.PROCEED, breaker.rebuild(SHRUNK))
    }
}
