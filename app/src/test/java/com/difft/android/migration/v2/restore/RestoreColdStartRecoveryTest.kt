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

    

    @Test
    fun `no marker returns Nothing and never calls PromoteStep or the flag`() = runTest {
        
        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.Nothing, outcome)
        
        coVerify(exactly = 0) { promoteStep.resumeIfPending() }
        verify(exactly = 0) { flagStore.advance(any()) }
        verify(exactly = 0) { flagStore.clear() }
    }

    @Test
    fun `no marker means the marker file truly does not exist (fast-path precondition)`() {
        
        assertTrue(!File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME).exists())
    }

    

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
        
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.NothingToResume

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.Nothing, outcome)
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    

    @Test
    fun `discarded half-promote clears the flag and reports AbortedToCleanState (not SIGNING_IN)`() = runTest {
        writeMarker()
        every { flagStore.clear() } just Runs
        
        
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.DiscardedToCleanState

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.AbortedToCleanState, outcome)
        verify(exactly = 1) { flagStore.clear() }
        
        verify(exactly = 0) { flagStore.advance(any()) }
    }

    @Test
    fun `resume finishes the promote regardless of login state (Fix 1 not-logged-in)`() = runTest {
        
        
        writeMarker()
        coEvery { promoteStep.resumeIfPending() } returns PromoteStep.ResumeResult.ResumedWithData

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        assertEquals(RestoreColdStartRecovery.Outcome.PromoteResumed, outcome)
        coVerify(exactly = 1) { promoteStep.resumeIfPending() }
        verify(exactly = 1) { flagStore.advance(RestorePhase.SIGNING_IN) }
    }

    

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

    

    @Test
    fun `retryable failure keeps the flag and reports RetryableFailure`() = runTest {
        writeMarker()
        val err = MigrationException.RestoreFailed("transient move failure")
        coEvery { promoteStep.resumeIfPending() } throws err

        val outcome = recovery.resumeIfNeeded(promoteStep, flagStore)

        val retry = assertIs<RestoreColdStartRecovery.Outcome.RetryableFailure>(outcome)
        assertEquals(err, retry.error)
        
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
