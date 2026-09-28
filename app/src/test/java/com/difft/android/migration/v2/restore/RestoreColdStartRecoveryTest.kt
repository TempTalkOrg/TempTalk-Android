package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the dead-safe cold-start restore resume (A4 c4 — design §3 T11, §5).
 *
 * The critical assertion is the HOT-PATH guarantee: with NO promote-marker on disk (the 99.99%
 * normal-launch case) [RestoreColdStartRecovery.resumeIfNeeded] returns [RestoreColdStartRecovery.Outcome.Nothing]
 * and NEVER touches [PromoteStep] or the flag — zero side effects, only one `File.exists()`.
 *
 * [PromoteStep] / [RestoreFlagStore] are mocked (the real ones need native WCDB / Keystore — that
 * end-to-end is `// TODO(A6 on-device)`); the marker presence is driven by a real
 * [PromoteMarkerStore] writing to `filesDir` (pure JVM Gson + file IO).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreColdStartRecoveryTest {

    private lateinit var context: Context
    private lateinit var recovery: RestoreColdStartRecovery
    private lateinit var markerStore: PromoteMarkerStore
    private lateinit var promoteStep: PromoteStep
    private lateinit var flagStore: RestoreFlagStore

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        recovery = RestoreColdStartRecovery(context)
        markerStore = PromoteMarkerStore(context)
        markerStore.clear()
        promoteStep = mockk()
        flagStore = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        markerStore.clear()
    }

    // ── HOT PATH: no marker → immediate no-op, zero side effects ───────────────────────────────

    @Test
    fun `no marker returns Nothing and never calls PromoteStep or the flag`() = runTest {
        // No marker on disk (normal launch).
        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.Nothing, outcome)
        // Zero side effects: resume engine untouched, flag untouched.
        coVerify(exactly = 0) { promoteStep.resumeIfPending() }
        verify(exactly = 0) { flagStore.advance(any()) }
        verify(exactly = 0) { flagStore.clear() }
    }

    @Test
    fun `no marker means the marker file truly does not exist (fast-path precondition)`() {
        // Documents the single cheap check the hot path relies on.
        assertTrue(!File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME).exists())
    }

    // ── COLD PATH: marker present → resume + advance flag to SIGNING_IN ─────────────────────────

    @Test
    fun `marker present and resume with data advances flag to SIGNING_IN`() = runTest {
        writeMarker()
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.ResumedWithData

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.PromoteResumed, outcome)
        coVerify(exactly = 1) { promoteStep.resumeIfPending() }
        verify(exactly = 1) { flagStore.advance(RestorePhase.SIGNING_IN) }
    }

    @Test
    fun `marker vanished between exists and resume returns Nothing`() = runTest {
        writeMarker()
        // resumeIfPending found no marker (concurrent clear) → NothingToResume.
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.NothingToResume

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.Nothing, outcome)
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    // ── Fix 4: discard-to-clean-state must NOT masquerade as a successful resume ────────────────

    @Test
    fun `discarded half-promote clears the flag and reports AbortedToCleanState (not SIGNING_IN)`() = runTest {
        writeMarker()
        every { flagStore.clear() } just Runs
        // PromoteStep discarded a half-promote (all staging products gone, or corrupt marker) —
        // NO data on disk. Mapping this to PromoteResumed/SIGNING_IN would make A5 sign in against an
        // EMPTY DB. It must instead clear the flag and report AbortedToCleanState (A5 re-prompts).
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.DiscardedToCleanState

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.AbortedToCleanState, outcome)
        verify(exactly = 1) { flagStore.clear() }
        // Crucially NOT advanced to SIGNING_IN (that would sign in against an empty DB).
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    @Test
    fun `resume finishes the promote regardless of login state (Fix 1 not-logged-in)`() = runTest {
        // Fix 1: the promote runs DURING login, BEFORE signIn. A crash mid-promote leaves the user
        // NOT logged in with a half-promote still to finish. The cold-start recovery takes NO login
        // input and depends on nothing login-related — only the promote-marker's presence drives it,
        // so it finishes the promote in the not-logged-in case exactly as in any other. This guards
        // against re-introducing a `loggedIn` gate that would skip recovery precisely when needed.
        writeMarker()
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.ResumedWithData

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.PromoteResumed, outcome)
        coVerify(exactly = 1) { promoteStep.resumeIfPending() }
        verify(exactly = 1) { flagStore.advance(RestorePhase.SIGNING_IN) }
    }

    // ── COLD PATH: unrecoverable abort → clear flag, clean state ───────────────────────────────

    @Test
    fun `unrecoverable abort clears the flag and reports AbortedToCleanState`() = runTest {
        writeMarker()
        every { flagStore.clear() } just Runs
        coEvery { promoteStep.resumeIfPending() } throws
            MigrationException.RestoreAbortedCleanState("staging key gone")

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.AbortedToCleanState, outcome)
        verify(exactly = 1) { flagStore.clear() }
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    // ── COLD PATH: retryable failure → keep marker, report RetryableFailure ─────────────────────

    @Test
    fun `retryable failure keeps the flag and reports RetryableFailure`() = runTest {
        writeMarker()
        val err = MigrationException.RestoreFailed("transient move failure")
        coEvery { promoteStep.resumeIfPending() } throws err

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        val retry = assertIs<RestoreColdStartRecovery.Outcome.RetryableFailure>(outcome)
        assertEquals(err, retry.error)
        // Marker NOT cleared here (PromoteStep owns that); flag left for next cold start.
        verify(exactly = 0) { flagStore.clear() }
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    private fun writeMarker() {
        markerStore.write(
            PromoteMarker(
                stagingDbPath = "/data/staging.db",
                protectedStagingKeyB64 = "QUJD",
                attachmentsStagingDir = "/data/att",
                realDbPath = "/data/tt_wcdb_database.db",
                realAttachmentDir = "/data/attachment",
            )
        )
    }
}
