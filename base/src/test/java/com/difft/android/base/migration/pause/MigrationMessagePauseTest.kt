package com.difft.android.base.migration.pause

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import com.difft.android.base.migration.pause.MessagingPauseState.Loading
import com.difft.android.base.migration.pause.MessagingPauseState.Open
import com.difft.android.base.migration.pause.MessagingPauseState.Paused
import com.difft.android.base.storage.AppStateKeys
import com.difft.android.test.fakes.RecordingPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue


class MigrationMessagePauseTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val owner = "11111111-aaaa-bbbb-cccc-000000000001"
    private val other = "22222222-aaaa-bbbb-cccc-000000000002"

    
    private class FlakyDataStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var readFailures = 0
        var writeFailures = 0

        override val data: Flow<Preferences>
            get() = flow {
                if (readFailures > 0) {
                    readFailures--
                    throw IOException("read")
                }
                emitAll(delegate.data)
            }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            if (writeFailures > 0) {
                writeFailures--
                throw IOException("write")
            }
            return delegate.updateData(transform)
        }
    }

    private val disk = RecordingPreferencesDataStore()
    private val store = FlakyDataStore(disk)
    private val gate = MessagingGate()
    private val pause = MigrationMessagePause(store, gate)

    private suspend fun stored(): String? = disk.data.first()[AppStateKeys.MIGRATION_MESSAGE_PAUSE]

    private suspend fun storedRecord(): MigrationPauseRecord? = stored()?.let(MigrationPauseRecordCodec::decode)

    private suspend fun receipt(): String? = disk.data.first()[AppStateKeys.MIGRATION_PENDING_VERDICT]

    private fun saveReceipt(value: String) = PendingVerdictEdit { it[AppStateKeys.MIGRATION_PENDING_VERDICT] = value }

    private val dropReceipt = PendingVerdictEdit { it.remove(AppStateKeys.MIGRATION_PENDING_VERDICT) }

    private suspend fun loadedAndBegun() {
        pause.load()
        assertTrue(pause.begin(owner, "scope", "cred", now = 1_000L))
    }

    private suspend fun undecided() {
        loadedAndBegun()
        assertEquals(PauseTransition.APPLIED, pause.committing(owner, "session", saveReceipt("receipt")))
        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.UNKNOWN))
    }

    @Test
    fun `starts loading and treats loading as closed`() {
        assertEquals(Loading, pause.state)
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "job"))
    }

    @Test
    fun `load with no record opens the gate`() = runTest {
        pause.load()
        assertEquals(Open, pause.onChanged.value)
        gate.tryAdmit(AdmissionKind.RECEIVE, "ws")!!.close()
    }

    @Test
    fun `read failures keep loading with backoff until a read succeeds`() = runTest {
        store.readFailures = 3
        val job = launch { pause.load() }
        runCurrent()
        assertEquals(Loading, pause.state)
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "fcm"))
        advanceTimeBy(1_000)
        assertEquals(Loading, pause.state)
        advanceTimeBy(60_000)
        job.join()
        assertEquals(Open, pause.state)
        assertEquals(0, store.readFailures)
    }

    @Test
    fun `begin persists exporting and closes the gate`() = runTest {
        loadedAndBegun()
        val expected = MigrationPauseRecord(owner, MigrationPausePhase.EXPORTING, "scope", "cred", null, 1_000L)
        assertEquals(expected, storedRecord())
        assertEquals(Paused(expected), pause.state)
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "ui"))
    }

    @Test
    fun `begin fails while a pause is in effect`() = runTest {
        loadedAndBegun()
        assertFalse(pause.begin(other, "scope", "cred"))
        assertEquals(owner, storedRecord()?.owner)
    }

    @Test
    fun `begin fails on a corrupt record`() = runTest {
        disk.emit(mutablePreferencesOf(AppStateKeys.MIGRATION_MESSAGE_PAUSE to "{garbage"))
        pause.load()
        assertFalse(pause.begin(owner, "scope", "cred"))
        assertEquals("{garbage", stored())
    }

    @Test
    fun `begin refuses a record awaiting a decision and keeps its receipt`() = runTest {
        undecided()

        assertFalse(pause.begin(other, "scope", "cred", now = 2_000L))

        assertEquals(owner, storedRecord()?.owner)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, storedRecord()?.phase)
        assertEquals("receipt", receipt())
    }

    @Test
    fun `begin refuses while a confirmed import awaits the old login`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session")
        pause.finish(owner, null, MigrationPauseOutcome.SUCCESS)

        assertFalse(pause.begin(other, "scope", "cred"))

        assertEquals(owner, storedRecord()?.owner)
    }

    @Test
    fun `begin before load completes reads the disk itself`() = runTest {
        assertTrue(pause.begin(owner, "scope", "cred"))
        assertIs<Paused>(pause.state)
        pause.load()
        assertEquals(owner, pause.record?.owner)
    }

    @Test
    fun `the commit marker stores the receipt in the same write`() = runTest {
        loadedAndBegun()

        assertEquals(PauseTransition.APPLIED, pause.committing(owner, "session", saveReceipt("receipt")))

        assertEquals(MigrationPausePhase.COMMITTING, storedRecord()?.phase)
        assertEquals("session", storedRecord()?.sessionId)
        assertEquals("receipt", receipt())
    }

    @Test
    fun `a commit marker the record does not take writes no receipt`() = runTest {
        loadedAndBegun()

        assertEquals(PauseTransition.SKIPPED, pause.committing(other, "session", saveReceipt("receipt")))

        assertNull(receipt())
        assertEquals(MigrationPausePhase.EXPORTING, storedRecord()?.phase)
    }

    @Test
    fun `a commit marker with no record reports no record`() = runTest {
        pause.load()
        assertEquals(PauseTransition.NO_RECORD, pause.committing(owner, "session", saveReceipt("receipt")))
        assertNull(receipt())
    }

    @Test
    fun `finish outcomes persist and publish, and a confirmed import is final`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session")
        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.UNKNOWN))
        assertEquals(MigrationPausePhase.AWAITING_DECISION, storedRecord()?.phase)
        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.SUCCESS))
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, storedRecord()?.phase)
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, (pause.state as? Paused)?.record?.phase)
        assertEquals(PauseTransition.SKIPPED, pause.finish(owner, null, MigrationPauseOutcome.FAILED))
        assertEquals(MigrationPausePhase.AWAITING_LOGIN, storedRecord()?.phase)
    }

    @Test
    fun `a record awaiting a decision keeps messaging paused`() = runTest {
        undecided()

        assertEquals(Paused(storedRecord()), pause.state)
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "job"))
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        assertEquals("session", pause.record?.sessionId)
    }

    @Test
    fun `a reloaded record awaiting a decision stays paused`() = runTest {
        undecided()
        val restartedGate = MessagingGate()
        val restarted = MigrationMessagePause(store, restartedGate)

        restarted.load()

        assertIs<Paused>(restarted.state)
        assertEquals(MigrationPausePhase.AWAITING_DECISION, restarted.record?.phase)
        assertNull(restartedGate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
    }

    @Test
    fun `only a definite verdict ends a decision pending`() = runTest {
        undecided()

        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.UNKNOWN))
        assertIs<Paused>(pause.state)
        assertEquals(PauseTransition.SKIPPED, pause.finish(owner, null, MigrationPauseOutcome.NOT_COMMITTED))
        assertIs<Paused>(pause.state)

        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.FAILED, dropReceipt))
        assertEquals(Open, pause.state)
        assertNull(stored())
        assertNull(receipt())
    }

    @Test
    fun `the cleanup edit runs only when the transition applied`() = runTest {
        undecided()
        pause.finish(owner, null, MigrationPauseOutcome.SUCCESS)

        
        assertEquals(PauseTransition.SKIPPED, pause.finish(owner, null, MigrationPauseOutcome.FAILED, dropReceipt))
        assertEquals("receipt", receipt())
        assertEquals(PauseTransition.SKIPPED, pause.finish(other, null, MigrationPauseOutcome.SUCCESS, dropReceipt))
        assertEquals("receipt", receipt())
    }

    @Test
    fun `a settlement named by another session finds no record`() = runTest {
        undecided()

        assertEquals(PauseTransition.NO_RECORD, pause.finish(null, "another-session", MigrationPauseOutcome.FAILED, dropReceipt))

        assertEquals("receipt", receipt())
        assertEquals(MigrationPausePhase.AWAITING_DECISION, storedRecord()?.phase)
        assertEquals(PauseTransition.APPLIED, pause.finish(null, "session", MigrationPauseOutcome.FAILED, dropReceipt))
        assertNull(receipt())
    }

    @Test
    fun `a settlement whose record was cleared under it is no record`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session", saveReceipt("receipt"))
        pause.clear(null, "signed_out")

        assertEquals(PauseTransition.NO_RECORD, pause.finish(owner, null, MigrationPauseOutcome.FAILED, saveReceipt("resurrected")))
        assertNull(receipt())
    }

    @Test
    fun `a pending edit is withheld while the record still waits on that session`() = runTest {
        undecided()

        assertTrue(pause.editPendingUnlessHeld("session", dropReceipt))
        assertEquals("receipt", receipt())

        assertTrue(pause.editPendingUnlessHeld("another-session", dropReceipt))
        assertNull(receipt())
    }

    @Test
    fun `scope check clears a record of another login with its receipt`() = runTest {
        undecided()

        pause.discardIfOutOfScope("scope", "new-cred")

        assertNull(stored())
        assertNull(receipt())
        assertNull(pause.record)
        assertEquals(Open, pause.state)
    }

    @Test
    fun `logout removes the record and the receipt and keeps messaging closed`() = runTest {
        undecided()

        assertTrue(pause.clearForLogout())

        assertNull(stored())
        assertNull(receipt())
        assertEquals(Paused(null), gate.state)
    }

    @Test
    fun `logout without a pause still drops a receipt and leaves messaging open`() = runTest {
        disk.emit(mutablePreferencesOf(AppStateKeys.MIGRATION_PENDING_VERDICT to "link-receipt"))
        pause.load()

        assertTrue(pause.clearForLogout())

        assertNull(receipt())
        assertEquals(Open, gate.state)
    }

    @Test
    fun `finish failed clears`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session")
        assertEquals(PauseTransition.APPLIED, pause.finish(owner, null, MigrationPauseOutcome.FAILED))
        assertNull(stored())
        assertEquals(Open, pause.state)
    }

    @Test
    fun `another owner changes nothing`() = runTest {
        loadedAndBegun()
        val before = stored()
        assertEquals(PauseTransition.SKIPPED, pause.committing(other, "x"))
        assertEquals(PauseTransition.SKIPPED, pause.finish(other, null, MigrationPauseOutcome.FAILED))
        pause.clear(other, "test")
        assertEquals(before, stored())
        assertEquals(owner, pause.record?.owner)
    }

    @Test
    fun `clear without owner clears any record and its receipt`() = runTest {
        undecided()
        pause.finish(owner, null, MigrationPauseOutcome.SUCCESS)
        assertTrue(pause.clear(null, "signed_out"))
        assertNull(stored())
        assertNull(receipt())
        assertEquals(Open, pause.state)
    }

    @Test
    fun `scope check keeps the same login and discards another`() = runTest {
        loadedAndBegun()
        pause.discardIfOutOfScope("scope", "cred")
        assertEquals(owner, storedRecord()?.owner)
        pause.discardIfOutOfScope("scope", "new-cred")
        assertNull(stored())
        assertEquals(Open, pause.state)
    }

    @Test
    fun `scope check discards another account`() = runTest {
        loadedAndBegun()
        pause.discardIfOutOfScope("another", "cred")
        assertNull(stored())
    }

    @Test
    fun `a corrupt record stays paused until logout`() = runTest {
        disk.emit(mutablePreferencesOf(AppStateKeys.MIGRATION_MESSAGE_PAUSE to """{"owner":"$owner"}"""))
        pause.load()
        assertEquals(Paused(null), pause.state)
        assertTrue(pause.hasRecord)
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "ws"))
        
        assertEquals(PauseTransition.SKIPPED, pause.finish(owner, null, MigrationPauseOutcome.FAILED))
        assertEquals(PauseTransition.SKIPPED, pause.finish(null, "session", MigrationPauseOutcome.FAILED))
        pause.clear(owner, "test")
        pause.discardIfOutOfScope("scope", "cred")
        pause.discardIfOutOfScope("another", "new-cred")
        assertEquals(Paused(null), pause.state)
        assertTrue(stored() != null)

        assertTrue(pause.clearForLogout())
        assertNull(stored())
    }

    @Test
    fun `a failed write leaves disk and gate unchanged and reports it`() = runTest {
        loadedAndBegun()
        store.writeFailures = 1
        assertEquals(PauseTransition.WRITE_FAILED, pause.finish(owner, null, MigrationPauseOutcome.FAILED))
        assertEquals(owner, storedRecord()?.owner)
        assertIs<Paused>(pause.state)
        store.writeFailures = 1
        assertFalse(pause.clear(null, "logout"))
        assertIs<Paused>(pause.state)
    }

    @Test
    fun `a write from a cancelled coroutine still commits`() = runTest {
        loadedAndBegun()
        val job = launch {
            currentCoroutineContext().cancel()
            pause.finish(owner, null, MigrationPauseOutcome.FAILED)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertNull(stored())
        assertEquals(Open, pause.state)
    }

    @Test
    fun `logout removes the stored pause but keeps messaging closed in this process`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session")
        pause.finish(owner, null, MigrationPauseOutcome.SUCCESS)

        assertTrue(pause.clearForLogout())

        assertNull(stored(), "the next process must start open")
        assertEquals(Paused(null), gate.state, "the ending login must not receive or send meanwhile")
        assertNull(gate.tryAdmit(AdmissionKind.RECEIVE, "test"))
    }

    @Test
    fun `a stale write after logout keeps messaging closed until the next login check`() = runTest {
        loadedAndBegun()
        assertTrue(pause.clearForLogout())

        
        assertEquals(PauseTransition.NO_RECORD, pause.committing(owner, "session"))
        assertEquals(Paused(null), gate.state)
        assertNull(gate.tryAdmit(AdmissionKind.SEND, "test"))

        pause.discardIfOutOfScope("other-scope", "other-cred")
        assertEquals(Open, gate.state)
    }

    @Test
    fun `a restarted process reloads the paused record`() = runTest {
        loadedAndBegun()
        pause.committing(owner, "session")
        val restarted = MigrationMessagePause(store, MessagingGate())
        assertEquals(Loading, restarted.state)
        restarted.load()
        assertEquals(MigrationPausePhase.COMMITTING, restarted.record?.phase)
        assertEquals("session", restarted.record?.sessionId)
    }

    @Test
    fun `record survives a real preferences file across instances`() = runTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val file = tmp.newFile("app_state.preferences_pb").also { it.delete() }
        try {
            val real = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            val first = MigrationMessagePause(real, MessagingGate())
            first.load()
            assertTrue(first.begin(owner, "scope", "cred", now = 7L))
            val second = MigrationMessagePause(real, MessagingGate())
            second.load()
            assertEquals(MigrationPauseRecord(owner, MigrationPausePhase.EXPORTING, "scope", "cred", null, 7L), second.record)
        } finally {
            scope.cancel()
        }
    }
}
