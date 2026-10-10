package com.difft.android.call.manager

import android.media.Ringtone
import com.difft.android.base.call.CallRole
import com.difft.android.base.call.CallType
import com.difft.android.call.R
import com.difft.android.call.manager.CallRingtoneManager.Companion.HANGUP_RELEASE_DELAY_MS
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * State machine of [CallRingtoneManager] (issue #1313): a stop racing a `play()` must leave
 * exactly one `stop()` and no `play()` after it, stale requests are dropped whatever their
 * enqueue order, and the hangup tone survives the teardown stop and releases itself once.
 *
 * Every fake [Ringtone] reports `isPlaying == true` after `play()` and `false` after `stop()`,
 * as the framework does; a relaxed mock's constant `false` would make the loop replay without a
 * suspension point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallRingtoneManagerTest {

    private lateinit var source: CallRingtoneSource

    /** Ringtones handed out by [CallRingtoneSource.create] in order; a fresh fake once empty. */
    private val queued = ArrayDeque<Ringtone?>()

    @Before
    fun setUp() {
        source = mockk()
        every { source.isRingerAudible() } returns true
        every { source.create(any()) } answers { if (queued.isEmpty()) fakeRingtone() else queued.removeFirst() }
    }

    private fun TestScope.manager() = CallRingtoneManager(source, StandardTestDispatcher(testScheduler), backgroundScope)

    private fun fakeRingtone(onPlay: () -> Unit = {}): Ringtone {
        val playing = AtomicBoolean(false)
        val rt = mockk<Ringtone>(relaxed = true)
        every { rt.isPlaying } answers { playing.get() }
        every { rt.play() } answers {
            onPlay()
            playing.set(true)
        }
        every { rt.stop() } answers { playing.set(false) }
        return rt
    }

    private fun queue(vararg rts: Ringtone?) = rts.forEach { queued.addLast(it) }

    // ---- R1–R3: the field race and its regression checklist ----

    @Test
    fun `R1 stop issued while play runs stops the tone once and never replays`() = runTest {
        lateinit var m: CallRingtoneManager
        val rt = fakeRingtone(onPlay = { m.stopRingTone("accept") })
        queue(rt)
        m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(5_000)

        verifyOrder {
            rt.play()
            rt.stop()
        }
        verify(exactly = 1) { rt.play() }
        verify(exactly = 1) { rt.stop() }
    }

    @Test
    fun `R2 three accept-path stops release the tone exactly once`() = runTest {
        val rt = fakeRingtone()
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        repeat(3) { m.stopRingTone("accept-$it") }
        advanceTimeBy(5_000)

        verify(exactly = 1) { rt.play() }
        verify(exactly = 1) { rt.stop() }
    }

    @Test
    fun `R3 play followed by stop before it runs never creates a tone`() = runTest {
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        m.stopRingTone()
        advanceTimeBy(1_000)

        verify(exactly = 0) { source.create(any()) }
    }

    @Test
    fun `R4 reject path stops the incoming tone before the hangup tone plays`() = runTest {
        val rtIn = fakeRingtone()
        val rtHang = fakeRingtone()
        queue(rtIn, rtHang)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        m.stopRingTone()
        m.playHangupRingTone()
        advanceTimeBy(1_000)

        verifyOrder {
            rtIn.stop()
            rtHang.play()
        }
        verify(exactly = 1) { rtIn.play() }
        verify(exactly = 1) { rtIn.stop() }
        verify(exactly = 1) { rtHang.play() }
        verify(exactly = 0) { rtHang.stop() }
        verify(exactly = 1) { source.create(R.raw.new_call_hangup) }
    }

    // ---- R5–R9: preserved loop behaviour ----

    @Test
    fun `R5 silent backoff polls once per second and stops polling after stop`() = runTest {
        every { source.isRingerAudible() } returns false
        val rt = fakeRingtone()
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(3_500)
        verify(exactly = 4) { source.isRingerAudible() }

        m.stopRingTone()
        advanceTimeBy(5_000)

        verify(exactly = 4) { source.isRingerAudible() }
        verify(exactly = 0) { rt.play() }
        verify(exactly = 1) { rt.stop() }
    }

    @Test
    fun `R6 loop resumes once the ringer becomes audible`() = runTest {
        every { source.isRingerAudible() } returnsMany listOf(false, false, true)
        val rt = fakeRingtone()
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(1_900)
        verify(exactly = 0) { rt.play() }
        advanceTimeBy(200)

        verify(exactly = 1) { rt.play() }
    }

    @Test
    fun `R7 group callee tone plays once without loop or ringer check`() = runTest {
        val rt = mockk<Ringtone>(relaxed = true)
        every { rt.isPlaying } returns false
        queue(rt)
        val m = manager()

        m.playRingTone(GROUP, CALLEE)
        advanceTimeBy(10_000)

        verify(exactly = 1) { source.create(R.raw.new_call_incomming_group) }
        verify(exactly = 1) { rt.play() }
        verify(exactly = 0) { source.isRingerAudible() }
    }

    @Test
    fun `R8 a new play replaces the previous loop which never replays`() = runTest {
        val rtA = fakeRingtone()
        val rtB = fakeRingtone()
        queue(rtA, rtB)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLER)
        runCurrent()
        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(1_000)

        verifyOrder {
            rtA.play()
            rtA.stop()
            rtB.play()
        }
        verify(exactly = 1) { rtA.play() }
        verify(exactly = 0) { rtB.stop() }
        verify(exactly = 1) { source.create(R.raw.new_call_outgoing_1v1) }
        verify(exactly = 1) { source.create(R.raw.new_call_incomming_1v1) }
    }

    @Test
    fun `R9 1v1 loop replays after each natural completion`() = runTest {
        // Reports playing for three polls after each play(), then completes.
        val playing = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val rt = mockk<Ringtone>(relaxed = true)
        every { rt.play() } answers {
            playing.set(true)
            reads.set(0)
        }
        every { rt.isPlaying } answers {
            when {
                !playing.get() -> false
                reads.getAndIncrement() < 3 -> true
                else -> false.also { playing.set(false) }
            }
        }
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(1_000)

        verify(atLeast = 2) { rt.play() }
        verify(exactly = 0) { rt.stop() }
    }

    // ---- R10–R14: cleanup, unknown type, error paths ----

    @Test
    fun `R10a cleanup invalidates a queued play synchronously`() = runTest {
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        m.cleanup()
        advanceTimeBy(1_000)

        verify(exactly = 0) { source.create(any()) }
    }

    @Test
    fun `R10b cleanup stops a playing tone on the serial dispatcher`() = runTest {
        val rt = fakeRingtone()
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        m.cleanup()
        runCurrent()
        verify(exactly = 1) { rt.stop() }

        advanceTimeBy(5_000)
        verify(exactly = 1) { rt.play() }
    }

    @Test
    fun `R11 an unknown call type still releases the current tone`() = runTest {
        val rt = fakeRingtone()
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        m.playRingTone("unknown", null)
        advanceTimeBy(1_000)

        verify(exactly = 1) { rt.stop() }
        verify(exactly = 1) { source.create(any()) }
    }

    @Test
    fun `R12 play throwing ends the loop and a later stop still releases`() = runTest {
        val rt = fakeRingtone()
        every { rt.play() } throws IllegalStateException("bad state")
        queue(rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(5_000)
        verify(exactly = 1) { rt.play() }

        m.stopRingTone()
        runCurrent()
        verify(exactly = 1) { rt.stop() }
    }

    @Test
    fun `R13 stop throwing NPE still clears state so the hangup tone plays`() = runTest {
        val rt = fakeRingtone()
        every { rt.stop() } throws NullPointerException("MediaPlayer.release")
        val rtHang = fakeRingtone()
        queue(rt, rtHang)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        m.stopRingTone()
        m.playHangupRingTone()
        advanceTimeBy(1_000)

        verify(exactly = 1) { rt.stop() }
        verify(exactly = 1) { rtHang.play() }
    }

    @Test
    fun `R14 a null ringtone installs nothing and the next play works`() = runTest {
        val rt = fakeRingtone()
        queue(null, rt)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()
        m.stopRingTone()
        runCurrent()
        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()

        verify(exactly = 2) { source.create(any()) }
        verify(exactly = 1) { rt.play() }
        verify(exactly = 0) { rt.stop() }
    }

    // ---- R16–R21: interleavings ----

    @Test
    fun `R16a a stale stop enqueued after a newer play leaves the new tone alone`() = runTest {
        val rtNew = fakeRingtone()
        queue(rtNew)
        val m = manager()

        val staleStop = m.issueStop("t1")
        m.playRingTone(ONE_ON_ONE, CALLEE)
        m.submitStop(staleStop, "t1")
        advanceTimeBy(1_000)

        verify(exactly = 1) { rtNew.play() }
        verify(exactly = 0) { rtNew.stop() }

        m.stopRingTone("later")
        runCurrent()
        verify(exactly = 1) { rtNew.stop() }
    }

    @Test
    fun `R16b the newer play releases the old tone when the stale stop is skipped`() = runTest {
        val rtOld = fakeRingtone()
        val rtNew = fakeRingtone()
        queue(rtOld, rtNew)
        val m = manager()
        m.playRingTone(ONE_ON_ONE, CALLER)
        runCurrent()

        val staleStop = m.issueStop("t1")
        m.playRingTone(ONE_ON_ONE, CALLEE)
        m.submitStop(staleStop, "t1")
        advanceTimeBy(1_000)

        verify(exactly = 1) { rtOld.stop() }
        verify(exactly = 1) { rtNew.play() }
        verify(exactly = 0) { rtNew.stop() }
    }

    @Test
    fun `R17 teardown stop after the hangup task ran leaves the hangup tone`() = runTest {
        val rtHang = fakeRingtone()
        queue(rtHang)
        val m = manager()

        m.playHangupRingTone()
        runCurrent()
        m.stopRingTone("cleanup")
        advanceTimeBy(1_000)

        verify(exactly = 1) { rtHang.play() }
        verify(exactly = 0) { rtHang.stop() }
    }

    @Test
    fun `R18a teardown stop issued before the hangup task runs does not skip it`() = runTest {
        val rtHang = fakeRingtone()
        queue(rtHang)
        val m = manager()

        m.playHangupRingTone()
        m.stopRingTone("cleanup")
        advanceTimeBy(1_000)

        verify(exactly = 1) { source.create(R.raw.new_call_hangup) }
        verify(exactly = 1) { rtHang.play() }
        verify(exactly = 0) { rtHang.stop() }
    }

    @Test
    fun `R18b teardown stop enqueued before the hangup task does not skip it`() = runTest {
        val rtPrev = fakeRingtone()
        val rtHang = fakeRingtone()
        queue(rtPrev, rtHang)
        val m = manager()
        m.playRingTone(ONE_ON_ONE, CALLEE)
        runCurrent()

        val hangup = m.issueTone("hangup", "hangup", "")
        m.stopRingTone("cleanup")
        m.submitHangup(hangup)
        advanceTimeBy(1_000)

        verify(exactly = 1) { rtPrev.stop() }
        verify(exactly = 1) { source.create(R.raw.new_call_hangup) }
        verify(exactly = 1) { rtHang.play() }
        verify(exactly = 0) { rtHang.stop() }
    }

    @Test
    fun `R19 the hangup tone releases itself exactly once after the delay`() = runTest {
        val rtHang = fakeRingtone()
        queue(rtHang)
        val m = manager()

        m.playHangupRingTone()
        runCurrent()
        advanceTimeBy(HANGUP_RELEASE_DELAY_MS - 1)
        verify(exactly = 0) { rtHang.stop() }

        advanceTimeBy(2)
        verify(exactly = 1) { rtHang.stop() }

        m.stopRingTone()
        advanceTimeBy(5_000)
        verify(exactly = 1) { rtHang.stop() }
    }

    @Test
    fun `R20 a play after the hangup supersedes it and self-release is a no-op`() = runTest {
        val rtHang = fakeRingtone()
        val rtIn = fakeRingtone()
        queue(rtHang, rtIn)
        val m = manager()

        m.playHangupRingTone()
        runCurrent()
        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(3_000)

        verifyOrder {
            rtHang.play()
            rtHang.stop()
            rtIn.play()
        }
        verify(exactly = 1) { rtHang.stop() }
        verify(exactly = 0) { rtIn.stop() }
    }

    @Test
    fun `R21a of two plays issued before either runs only the later one plays`() = runTest {
        val rtB = fakeRingtone()
        queue(rtB)
        val m = manager()

        m.playRingTone(ONE_ON_ONE, CALLER)
        m.playRingTone(ONE_ON_ONE, CALLEE)
        advanceTimeBy(1_000)

        assertSingleCalleeTone(rtB)
    }

    @Test
    fun `R21b an older play enqueued after a newer one is dropped`() = runTest {
        val rtB = fakeRingtone()
        queue(rtB)
        val m = manager()

        val older = m.issueTone("play", null, "")
        m.playRingTone(ONE_ON_ONE, CALLEE)
        m.submitPlay(older, ONE_ON_ONE, CALLER, null)
        advanceTimeBy(1_000)

        assertSingleCalleeTone(rtB)
    }

    private fun assertSingleCalleeTone(rtB: Ringtone) {
        verify(exactly = 1) { source.create(any()) }
        verify(exactly = 1) { source.create(R.raw.new_call_incomming_1v1) }
        verify(exactly = 1) { rtB.play() }
        verify(exactly = 0) { rtB.stop() }
    }

    private companion object {
        val ONE_ON_ONE = CallType.ONE_ON_ONE.type
        val GROUP = CallType.GROUP.type
        val CALLER = CallRole.CALLER.type
        val CALLEE = CallRole.CALLEE.type
    }
}
