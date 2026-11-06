package com.difft.android.migration.v2.pause

import com.difft.android.base.migration.pause.HeldSendJobs
import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingPauseState
import com.difft.android.base.migration.pause.MigrationMessagePause
import com.difft.android.base.migration.pause.MigrationPauseLoginScope
import com.difft.android.base.migration.pause.MigrationPauseOutcome
import com.difft.android.base.migration.pause.MigrationPausePhase
import com.difft.android.base.storage.user.CredentialChangeSignal
import com.difft.android.base.user.UserData
import com.difft.android.base.utils.EnvironmentHelper
import com.difft.android.migration.v2.transfer.PendingVerdict
import com.difft.android.migration.v2.transfer.PendingVerdictStore
import com.difft.android.migration.v2.transfer.SenderSessionRef
import com.difft.android.migration.v2.transfer.Verdict
import com.difft.android.test.fakes.FakeUserManager
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import com.difft.android.test.fakes.TestMessagingPause.Companion.OWNER
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** [MigrationPauseRecovery]: a pause that outlived its process resolves by phase (T5, T6, T7, T21, #1382). */
class MigrationPauseRecoveryTest {

    private val disk = RecordingPreferencesDataStore()
    private val users = FakeUserManager(UserData(account = "acc-1", baseAuth = "auth-1"))
    private val environment = mockk<EnvironmentHelper> { every { currentEnvironment } returns "online" }
    private val pendingStore = PendingVerdictStore(disk)
    private val heldSends = HeldSendJobs { _, _ -> }
    private val session = SenderSessionRef("10.0.0.2", 53317, "fp", SESSION, "token", listOf("status-v1"))

    /**
     * The process that crashed: pauses under the current login and reaches [phase]. Past the commit
     * marker the receipt was written in the marker's own edit unless [withReceipt] is false (a
     * receiver without `status-v1`).
     */
    private fun crashedIn(phase: MigrationPausePhase, withReceipt: Boolean = true) = runBlocking<Unit> {
        val pause = MigrationMessagePause(disk, MessagingGate())
        pause.load()
        val scope = assertNotNull(MigrationPauseLoginScope(users, environment, pause, CredentialChangeSignal(), heldSends).current())
        pause.begin(OWNER, scope.accountScope, scope.credentialHash)
        if (phase != MigrationPausePhase.EXPORTING) {
            val receipt = if (withReceipt) pendingStore.saveEdit(PendingVerdict(session, System.currentTimeMillis())) else null
            pause.committing(OWNER, SESSION, receipt)
        }
        when (phase) {
            MigrationPausePhase.AWAITING_LOGIN -> pause.finish(OWNER, null, MigrationPauseOutcome.SUCCESS)
            MigrationPausePhase.AWAITING_DECISION -> pause.finish(OWNER, null, MigrationPauseOutcome.UNKNOWN)
            else -> Unit
        }
    }

    /** The next process: loads the record and runs recovery with [verdict] as the receiver's answer. */
    private fun coldStart(verdict: Verdict? = null): MigrationMessagePause = coldStart { verdict ?: error("no receiver to ask") }

    private fun coldStart(answer: suspend (SenderSessionRef) -> Verdict): MigrationMessagePause = runBlocking {
        val pause = MigrationMessagePause(disk, MessagingGate())
        pause.startLoad(this)
        val settlement = SenderVerdictSettlement(pause, pendingStore)
        val recovery = MigrationPauseRecovery(
            pause,
            MigrationPauseLoginScope(users, environment, pause, CredentialChangeSignal(), heldSends),
            settlement,
            pendingStore,
            MigrationVerdictRecheck(pause, pendingStore, settlement),
        )
        recovery.probeVerdict = answer
        recovery.onColdStart()
        pause
    }

    @Test
    fun `a crash while exporting resumes messaging`() {
        crashedIn(MigrationPausePhase.EXPORTING)

        assertEquals(MessagingPauseState.Open, coldStart().state)
    }

    @Test
    fun `a crash after the marker with a promoted receiver awaits the old login`() = runBlocking<Unit> {
        crashedIn(MigrationPausePhase.COMMITTING)

        val pause = coldStart(Verdict.Succeeded)

        assertEquals(MigrationPausePhase.AWAITING_LOGIN, pause.record?.phase)
        assertNull(pendingStore.load())
    }

    @Test
    fun `a crash between the commit marker and the end marker asks with the receipt written alongside`() = runBlocking<Unit> {
        // The marker and the receipt landed together; the end marker never left. The receiver saw a
        // body that broke off and says failed, so messaging resumes and the receipt goes.
        crashedIn(MigrationPausePhase.COMMITTING)
        var asked: SenderSessionRef? = null

        val pause = coldStart { ref -> asked = ref; Verdict.Ended("failed") }

        assertEquals(SESSION, asked?.sessionId)
        assertEquals(MessagingPauseState.Open, pause.state)
        assertNull(pendingStore.load())
    }

    @Test
    fun `a crash after the marker with an unreachable receiver stays paused with the receipt`() = runBlocking<Unit> {
        crashedIn(MigrationPausePhase.COMMITTING)

        val pause = coldStart(Verdict.Unknown(Verdict.Unknown.Reason.UNREACHABLE))

        assertEquals(MigrationPausePhase.AWAITING_DECISION, pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(pause.state)
        assertNotNull(pendingStore.load())
    }

    @Test
    fun `a crash after the marker with an unrecognized terminal stays paused`() = runBlocking<Unit> {
        crashedIn(MigrationPausePhase.COMMITTING)

        val pause = coldStart(com.difft.android.migration.v2.transfer.VerdictWatch.verdictOf("aborted"))

        assertEquals(MigrationPausePhase.AWAITING_DECISION, pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(pause.state)
        assertNotNull(pendingStore.load())
    }

    @Test
    fun `a crash after the marker with no receipt awaits a decision, paused`() {
        crashedIn(MigrationPausePhase.COMMITTING, withReceipt = false)

        val pause = coldStart()

        assertEquals(MigrationPausePhase.AWAITING_DECISION, pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(pause.state)
    }

    @Test
    fun `a pause awaiting the old login is kept paused`() {
        crashedIn(MigrationPausePhase.AWAITING_LOGIN)
        val pause = coldStart()
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(pause.state)
    }

    @Test
    fun `a record awaiting a decision is kept paused with its receipt`() = runBlocking<Unit> {
        crashedIn(MigrationPausePhase.AWAITING_DECISION)
        val pause = coldStart()
        assertEquals(MigrationPausePhase.AWAITING_DECISION, pause.record?.phase)
        assertIs<MessagingPauseState.Paused>(pause.state)
        assertNotNull(pendingStore.load())
    }

    @Test
    fun `a record awaiting a decision from another login is discarded at cold start`() {
        crashedIn(MigrationPausePhase.AWAITING_DECISION)
        users.setUserData(UserData(account = "acc-2", baseAuth = "auth-1"))

        assertNull(coldStart().record)
    }

    @Test
    fun `a pause from another login is discarded at cold start`() {
        crashedIn(MigrationPausePhase.AWAITING_LOGIN)
        users.setUserData(UserData(account = "acc-1", baseAuth = "new-credential"))

        assertEquals(MessagingPauseState.Open, coldStart().state)
    }

    private companion object {
        const val SESSION = "session-1"
    }
}
