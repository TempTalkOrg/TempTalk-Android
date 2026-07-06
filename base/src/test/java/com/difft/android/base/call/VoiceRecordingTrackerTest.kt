package com.difft.android.base.call

import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [VoiceRecordingTracker].
 *
 * Covers test cases T1, T2, T3, T4, T15, T16 from
 * `tmp/voice-rec-fullscreen-suppress/design-report.md` §Test Case Inventory.
 *
 * The tracker is an `object` (singleton), so all tests share the same instance.
 * State is reset to `false` in both [setUp] and [tearDown] so each test starts
 * from a known-clean state regardless of test ordering.
 *
 * Pure JUnit4 + kotlin.test — no Robolectric, no MockK, no Android framework
 * (other than the unavoidable transitive dependency on `L`, which is a no-op
 * under unit test classpath when Timber has no trees planted).
 */
class VoiceRecordingTrackerTest {

    @Before
    fun setUp() {
        // Reset to clean state — previous test may have left isRecording = true.
        VoiceRecordingTracker.setRecording(false, "test_reset")
    }

    @After
    fun tearDown() {
        // Belt-and-suspenders: leave the singleton in clean state for the next class.
        VoiceRecordingTracker.setRecording(false, "test_reset")
    }

    /**
     * T1: `VoiceRecordingTracker.isRecording` initial value is false.
     *
     * Implementation note: because the tracker is a process-singleton, the
     * "initial value" assertion is order-dependent in a JVM that reuses the
     * class loader across tests. We assert it as a post-`@Before` precondition
     * — `setUp()` resets to false, so `isRecording` must be false at the start
     * of any test that touches the tracker.
     */
    @Test
    fun `initial value is false`() {
        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    /**
     * T2: `setRecording(true, "start")` flips `isRecording` to true.
     */
    @Test
    fun `setRecording true sets isRecording to true`() {
        VoiceRecordingTracker.setRecording(true, "start")

        assertEquals(true, VoiceRecordingTracker.isRecording)
    }

    /**
     * T3: After `setRecording(true, "start")` then `setRecording(false, "stop")`,
     * `isRecording` is false.
     */
    @Test
    fun `setRecording false after true sets isRecording to false`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "stop")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    /**
     * T4: Calling `setRecording(true, ...)` twice with the same value is idempotent.
     *
     * We can't directly assert "the transition log was not emitted twice" without
     * a captured logger (would require extra infrastructure). Instead we assert
     * the observable state remains consistent and no exception is thrown — the
     * production code's `if (previous != recording)` guard handles the log
     * dedup internally.
     */
    @Test
    fun `setRecording idempotent same value`() {
        VoiceRecordingTracker.setRecording(true, "first")
        VoiceRecordingTracker.setRecording(true, "second")

        assertTrue(
            VoiceRecordingTracker.isRecording,
            "Expected isRecording to remain true after two consecutive setRecording(true) calls"
        )
    }

    /**
     * T15: Tracker reset on detach path — symmetric with T3 but documents the
     * `VoiceRecorderView.onDetachedFromWindow()` reason string.
     */
    @Test
    fun `tracker reset on detach path`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "detach")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }

    /**
     * T16: Tracker reset on abort path — symmetric with T3 but documents the
     * abort scenario reason string (e.g., audio focus lost mid-recording).
     */
    @Test
    fun `tracker reset on abort path`() {
        VoiceRecordingTracker.setRecording(true, "start")
        VoiceRecordingTracker.setRecording(false, "abort")

        assertEquals(false, VoiceRecordingTracker.isRecording)
    }
}
