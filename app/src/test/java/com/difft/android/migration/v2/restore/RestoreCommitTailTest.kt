package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.user.UserManager
import com.difft.android.migration.model.MigrationError
import com.difft.android.migration.model.MigrationException
import com.difft.android.migration.model.RestorePhase
import com.difft.android.migration.v2.handoff.CredentialInstaller
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.difft.app.database.WCDBKeyManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T36-T38b: the commit boundary. Everything up to the database rename stays an ordinary, retryable
 * failure; from the rename on, the data is kept, no verdict is published, and the device finishes the
 * promote on its next cold start.
 *
 * Same strategy as `PromoteStepTest`: the file moves and the marker lifecycle run for real against
 * temp files, and only the Keystore-bound `WCDBKeyManager` calls are stubbed, because Robolectric does
 * not emulate the AndroidKeystore. The staging database itself is a relaxed mock — the tail only reads
 * its key and discards it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RestoreCommitTailTest {

    private lateinit var context: Context
    private lateinit var markerStore: PromoteMarkerStore
    private lateinit var promoteStep: PromoteStep
    private lateinit var workDir: File
    private lateinit var paths: RestorePaths

    private val stagingKey = ByteArray(48) { (it + 1).toByte() }
    private val protectedBlob = ByteArray(76) { (it * 3).toByte() }
    private val dbBytes = byteArrayOf(1, 2, 3, 4)

    private val userManager: UserManager = mockk(relaxed = true)
    private val credentialInstaller: CredentialInstaller = mockk(relaxed = true)
    private val cleaned = ArrayList<File?>()

    private val tail by lazy {
        RestoreCommitTail(
            context = context,
            userManager = userManager,
            credentialInstaller = credentialInstaller,
            publish = { _, _ -> },
            cleanupWork = { cleaned.add(it) },
        )
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markerStore = PromoteMarkerStore(context)
        promoteStep = PromoteStep(context, markerStore, UnconfinedTestDispatcher())
        workDir = File(context.cacheDir, "commit-tail-${System.nanoTime()}").apply { mkdirs() }
        paths = RestorePaths(
            realDbPath = File(workDir, "real/tt_wcdb_database.db").absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
            stagingDbPath = File(workDir, "work/staging.db").absolutePath,
            attachmentsStagingDir = File(workDir, "att-staging").absolutePath,
            containerWorkFile = File(workDir, "work/container.ttmig").absolutePath,
            spoolDir = File(workDir, "spool").absolutePath,
        )
        mockkObject(WCDBKeyManager)
        every { WCDBKeyManager.protectKey(any()) } returns protectedBlob
        every { WCDBKeyManager.unprotectKey(any()) } returns stagingKey
        every { WCDBKeyManager.installKey(any(), any()) } returns Unit
        markerStore.clear()
    }

    @After
    fun teardown() {
        unmockkObject(WCDBKeyManager)
        markerStore.clear()
        workDir.deleteRecursively()
    }

    // ── fixtures ───────────────────────────────────────────────────────────────────────────────

    /** Lay down the staging products a finished import leaves behind, plus a spool with real bytes. */
    private fun stageEverything() {
        File(paths.stagingDbPath).apply { parentFile!!.mkdirs(); writeBytes(dbBytes) }
        File(paths.stagingDbPath + "-wal").writeBytes(byteArrayOf(9))
        File(paths.containerWorkFile).writeBytes(byteArrayOf(7, 7))
        File(paths.attachmentsStagingDir).mkdirs()
        File(File(paths.attachmentsStagingDir), "a.bin").writeBytes(byteArrayOf(5, 6))
        File(paths.spoolDir).mkdirs()
        File(File(paths.spoolDir), "seg-00000000.bin").writeBytes(ByteArray(1024))
    }

    private fun staging(): StagingDatabase = mockk<StagingDatabase>(relaxed = true).also {
        every { it.stagingKey } returns stagingKey
    }

    /** A flag store that records its advances and fails the ones named in [failOn]. */
    private fun flagStore(vararg failOn: RestorePhase, advanced: MutableList<RestorePhase>): RestoreFlagStore =
        mockk<RestoreFlagStore>(relaxed = true).also { store ->
            every { store.advance(any()) } answers {
                val phase = firstArg<RestorePhase>()
                advanced.add(phase)
                if (phase in failOn) throw MigrationException.OutOfSpace("no room for the $phase write")
            }
        }

    private suspend fun runTail(flagStore: RestoreFlagStore, credential: com.difft.android.migration.v2.model.NeutralCredential? = null) =
        tail.run(
            staging = staging(),
            paths = paths,
            flagStore = flagStore,
            work = File(paths.containerWorkFile).parentFile,
            promoteStep = promoteStep,
            isFreshTarget = { true },
            carriedIdentity = null,
            credentialHandoff = credential != null,
            carriedCredential = credential,
        )

    private fun assertDataKept() {
        assertContentEquals(dbBytes, File(paths.realDbPath).readBytes(), "the promoted database is kept")
        assertContentEquals(byteArrayOf(5, 6), File(paths.realAttachmentDir, "a.bin").readBytes())
        assertTrue(
            PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath),
            "the rename ran, so this run is past the commit boundary",
        )
    }

    // ── T36 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a post-commit sign-in-flag failure keeps the data and publishes no verdict`() = runTest {
        stageEverything()
        val advanced = mutableListOf<RestorePhase>()
        val flagStore = flagStore(RestorePhase.SIGNING_IN, advanced = advanced)

        assertFailsWith<MigrationException.CommittedUnconfirmed> { runTail(flagStore) }

        assertDataKept()
        assertEquals(
            RestoreCommitTail.TAIL_ATTEMPTS,
            advanced.count { it == RestorePhase.SIGNING_IN },
            "the tail is retried a bounded number of times before it gives up",
        )
        assertTrue(cleaned.isEmpty(), "no work area is reclaimed on the committed-unconfirmed path")
    }

    @Test
    fun `a second run against a no-longer-fresh target is rejected, so no duplicate migration is possible`() = runTest {
        stageEverything()
        assertFailsWith<MigrationException.CommittedUnconfirmed> {
            runTail(flagStore(RestorePhase.SIGNING_IN, advanced = mutableListOf()))
        }

        val second = tail.run(
            staging = staging(),
            paths = paths,
            flagStore = mockk(relaxed = true),
            work = null,
            promoteStep = promoteStep,
            isFreshTarget = { false },
            carriedIdentity = null,
        )
        assertEquals(RestoreResult.Rejected(MigrationError.TargetNotEmpty), second)
    }

    // ── T37 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a failure writing the final result is committed-unconfirmed too`() = runTest {
        stageEverything()
        val credential = mockk<com.difft.android.migration.v2.model.NeutralCredential>(relaxed = true)
        every { credentialInstaller.install(credential) } returns CredentialInstaller.Result.Installed
        val advanced = mutableListOf<RestorePhase>()

        assertFailsWith<MigrationException.CommittedUnconfirmed> {
            runTail(flagStore(RestorePhase.COMPLETED, advanced = advanced), credential = credential)
        }

        assertDataKept()
        assertEquals(RestoreCommitTail.TAIL_ATTEMPTS, advanced.count { it == RestorePhase.COMPLETED })
    }

    @Test
    fun `a credential install skipped for a foreign account after the rename is committed-unconfirmed`() = runTest {
        stageEverything()
        val credential = mockk<com.difft.android.migration.v2.model.NeutralCredential>(relaxed = true)
        every { credentialInstaller.install(credential) } returns CredentialInstaller.Result.SkippedNotFresh
        val advanced = mutableListOf<RestorePhase>()

        assertFailsWith<MigrationException.CommittedUnconfirmed> {
            runTail(flagStore(advanced = advanced), credential = credential)
        }

        assertDataKept()
        assertEquals(1, advanced.count { it == RestorePhase.SIGNING_IN }, "a skipped install is final, never retried")
    }

    @Test
    fun `a credential already written by an earlier attempt completes the tail`() = runTest {
        stageEverything()
        val credential = mockk<com.difft.android.migration.v2.model.NeutralCredential>(relaxed = true)
        every { credentialInstaller.install(credential) } returns CredentialInstaller.Result.AlreadyInstalled
        val advanced = mutableListOf<RestorePhase>()

        assertEquals(null, runTail(flagStore(advanced = advanced), credential = credential))
        assertTrue(advanced.contains(RestorePhase.COMPLETED))
    }

    // ── T37b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a P1 key-install failure is an ordinary failure, not committed-unconfirmed`() = runTest {
        stageEverything()
        every { WCDBKeyManager.installKey(any(), any()) } throws IOException("keystore unavailable")

        val failure = assertFailsWith<MigrationException.RestoreFailed> {
            runTail(flagStore(advanced = mutableListOf()))
        }
        assertTrue(failure !is MigrationException.CommittedUnconfirmed)

        assertFalse(
            PromoteStep.isDatabasePromoted(paths.stagingDbPath, paths.realDbPath),
            "the rename never ran, so nothing is committed",
        )
        assertTrue(File(paths.stagingDbPath).exists(), "the staging database survives for the cold-start resume")
        assertTrue(File(paths.attachmentsStagingDir).exists(), "so do the staging attachments")
        assertTrue(markerStore.exists(), "and so does the marker the resume reads them through")
    }

    // ── T37c ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a pre-P2 failure is finished in process by the resume and the tail continues`() = runTest {
        stageEverything()
        var installs = 0
        every { WCDBKeyManager.installKey(any(), any()) } answers {
            if (installs++ == 0) throw IOException("transient keystore failure") else Unit
        }
        val advanced = mutableListOf<RestorePhase>()

        assertNull(runTail(flagStore(advanced = advanced)), "the tail completed, so the caller reports ReadyForSignIn")

        assertDataKept()
        assertTrue(advanced.contains(RestorePhase.SIGNING_IN), "the tail ran through to the sign-in flag")
        assertFalse(markerStore.exists(), "the resume's own P4 cleared the marker")
    }

    // ── T37d ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the tail reclaim deletes the spool and nothing else`() = runTest {
        stageEverything()
        val work = File(paths.containerWorkFile).parentFile!!
        val sentinel = File(work, "sentinel.bin").apply { writeBytes(byteArrayOf(42)) }

        assertFailsWith<MigrationException.CommittedUnconfirmed> {
            runTail(flagStore(RestorePhase.SIGNING_IN, advanced = mutableListOf()))
        }

        assertFalse(File(paths.spoolDir).exists(), "the spool is reclaimed")
        assertTrue(work.exists(), "the work directory the staging database lives in is never touched")
        assertContentEquals(byteArrayOf(42), sentinel.readBytes())
        assertTrue(File(paths.containerWorkFile).exists())
    }

    // ── T38 ────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a tail failure that clears on the retry completes normally`() = runTest {
        stageEverything()
        var attempts = 0
        val flagStore = mockk<RestoreFlagStore>(relaxed = true).also { store ->
            every { store.advance(RestorePhase.SIGNING_IN) } answers {
                if (attempts++ == 0) throw IOException("No space left on device") else Unit
            }
        }

        assertNull(runTail(flagStore), "the second attempt succeeded, so this is an ordinary success")
        assertEquals(2, attempts)
        assertDataKept()
    }

    // ── T38b ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a stuck flag with no marker left is cleared rather than left at PROMOTING`() = runTest {
        stageEverything()
        val flagStore = flagStore(RestorePhase.SIGNING_IN, advanced = mutableListOf())

        assertFailsWith<MigrationException.CommittedUnconfirmed> { runTail(flagStore) }

        assertFalse(markerStore.exists(), "P4 cleared the marker, so nothing would ever resume the flag")
        verify { flagStore.clear() }
        assertDataKept()
    }
}
