package com.difft.android.call.ui

import app.cash.turbine.test
import io.livekit.android.room.participant.Participant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for the `distinctUntilChanged` predicate used at
 * `MultiParticipantCallPage.kt:69-72`:
 *
 * ```kotlin
 * viewModel.participants.distinctUntilChanged { old, new ->
 *     old.size == new.size && old.zip(new).all { (a, b) -> a.sid == b.sid }
 * }
 * ```
 *
 * Coverage from `tmp/bug-anr-multiparticipant/design-report.md` §Test Strategy:
 *  1. Identical-ordered list → conflated (no downstream emission).
 *  2. Different `sid` → passes through.
 *  3. Reordered list → passes through (preserves the resort path's behavior).
 *  4. Size change → passes through.
 *
 * `Participant.Sid` is a `@JvmInline value class` wrapping a `String`. Value classes
 * compare via the wrapped value's `equals`, so `Participant.Sid("A") == Participant.Sid("A")`
 * is `true`. Tests construct real [Participant] instances with constructed [Participant.Sid]
 * values — no mocking needed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MultiParticipantCallPageDistinctTest {

    /**
     * Constructs a real [Participant] with a deterministic `sid`. The third constructor arg
     * is a [kotlinx.coroutines.CoroutineDispatcher]; the dispatcher is only used for
     * background bookkeeping (event bus / flow delegate) which is not exercised by the
     * predicate, so [Dispatchers.Unconfined] is sufficient and never spins up real work.
     */
    private fun participant(sidValue: String): Participant =
        Participant(
            sid = Participant.Sid(sidValue),
            identity = null,
            coroutineDispatcher = Dispatchers.Unconfined,
        )

    /** The exact predicate used in production at `MultiParticipantCallPage.kt:70-72`. */
    private val predicate: (List<Participant>, List<Participant>) -> Boolean = { old, new ->
        old.size == new.size && old.zip(new).all { (a, b) -> a.sid == b.sid }
    }

    // ----------------------------------------------------------------------------------
    // Case 1: Identical-ordered list — conflated (expectNoEvents)
    // ----------------------------------------------------------------------------------
    @Test
    fun `identical-ordered emission is conflated`() = runTest {
        val a = participant("A")
        val b = participant("B")
        val source = MutableStateFlow(listOf(a, b))

        source.distinctUntilChanged(areEquivalent = predicate).test {
            // initial value is replayed to the new collector
            val initial = awaitItem()
            assertEquals(2, initial.size)

            // Same instance list — predicate sees identical sids in identical order; conflated.
            source.value = listOf(a, b)
            expectNoEvents()

            // A fresh list whose elements have the SAME `sid.value` (different Participant
            // instances). Predicate compares `a.sid == b.sid`, so value-class equality wins
            // and the emission is conflated.
            val a2 = participant("A")
            val b2 = participant("B")
            source.value = listOf(a2, b2)
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----------------------------------------------------------------------------------
    // Case 2: Different sid — passes through
    // ----------------------------------------------------------------------------------
    @Test
    fun `different sid passes through`() = runTest {
        val a = participant("A")
        val b = participant("B")
        val source = MutableStateFlow(listOf(a, b))

        source.distinctUntilChanged(areEquivalent = predicate).test {
            awaitItem()  // initial [A, B]

            // Replace position 1 (B) with a brand-new sid (C). Predicate must NOT conflate.
            val c = participant("C")
            source.value = listOf(a, c)
            val emitted = awaitItem()
            assertEquals(2, emitted.size)
            assertSame(c, emitted[1])

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----------------------------------------------------------------------------------
    // Case 3: Reordered list — passes through (preserves resort path)
    // ----------------------------------------------------------------------------------
    @Test
    fun `reordered list passes through`() = runTest {
        val a = participant("A")
        val b = participant("B")
        val source = MutableStateFlow(listOf(a, b))

        source.distinctUntilChanged(areEquivalent = predicate).test {
            awaitItem()  // initial [A, B]

            // Swap positions: ParticipantManager.resortParticipants reorders entries on
            // priority change. Predicate uses ordered zip().all — must NOT conflate.
            source.value = listOf(b, a)
            val emitted = awaitItem()
            assertEquals(2, emitted.size)
            assertSame(b, emitted[0])
            assertSame(a, emitted[1])

            cancelAndIgnoreRemainingEvents()
        }
    }

    // ----------------------------------------------------------------------------------
    // Case 4: Size change — passes through
    // ----------------------------------------------------------------------------------
    @Test
    fun `size change passes through`() = runTest {
        val a = participant("A")
        val b = participant("B")
        val source = MutableStateFlow(listOf(a, b))

        source.distinctUntilChanged(areEquivalent = predicate).test {
            awaitItem()  // initial [A, B]

            // Add a third participant. Size differs from the previous emission.
            val c = participant("C")
            source.value = listOf(a, b, c)
            val emitted = awaitItem()
            assertEquals(3, emitted.size)

            // Drop back to two — still must emit, size differs from the previous emit (3).
            source.value = listOf(a, b)
            val emitted2 = awaitItem()
            assertEquals(2, emitted2.size)

            cancelAndIgnoreRemainingEvents()
        }
    }
}
