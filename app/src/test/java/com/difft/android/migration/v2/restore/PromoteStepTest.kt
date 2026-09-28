package com.difft.android.migration.v2.restore

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import com.difft.android.migration.model.MigrationException
import io.mockk.every
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM-level tests for the A4 promote critical section (design §4 P0..P4 + §5 crash recovery).
 *
 * Strategy: the file-move + marker-lifecycle logic is pure JVM and runs for REAL here against
 * temp files. Only the Keystore-bound calls ([WCDBKeyManager.protectKey] / [unprotectKey] /
 * [installKey]) are stubbed via `mockkObject` — Robolectric does not emulate the AndroidKeystore
 * (same limitation pinned in `WCDBKeyManagerTest`). The AES-GCM round-trip of the staging key and
 * the actual `wcdb_key.bin` write are therefore on-device concerns:
 *
 *   // TODO(A6 on-device): verify P1 installs the real staging key into wcdb_key.bin and a cold
 *   //   start opens the promoted DB with it. Needs a real device + native WCDB.
 *
 * What IS verified here at JVM level:
 *  - P0 marker is written before moves and cleared at P4.
 *  - P2 moves the staging db file (+ -wal/-shm sidecars) onto the real path.
 *  - P3 moves staging attachments onto the real attachment dir, idempotently.
 *  - promote does NOT re-assert net-install (caller's precondition) — it promotes regardless of the
 *    real DB's prior file state (the net-install row-count guard now lives in RestoreOrchestrator).
 *  - resumeIfPending re-does P1..P4 while any staging product survives, and safely discards a
 *    half-promote whose staging products are all gone (net-install invariant) — the discard decision
 *    is DB-free (staging-product presence), NOT the real DB's byte size.
 *  - PromoteMarkerStore atomic write / read / clear round-trips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromoteStepTest {

    private lateinit var context: Context
    private lateinit var markerStore: PromoteMarkerStore
    private lateinit var step: PromoteStep
    private lateinit var workDir: File

    /** A fixed 48-byte staging key; with WCDBKeyManager mocked the bytes pass through verbatim. */
    private val stagingKey = ByteArray(48) { (it + 1).toByte() }
    private val protectedBlob = ByteArray(76) { (it * 3).toByte() }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        markerStore = PromoteMarkerStore(context)
        // Pin file IO to the test scheduler so exceptions don't leak out of runTest onto real IO threads.
        step = PromoteStep(context, markerStore, UnconfinedTestDispatcher())
        workDir = File(context.cacheDir, "promote-test-${System.nanoTime()}").apply { mkdirs() }

        // Stub ONLY the Keystore-bound calls. File IO + marker lifecycle run for real.
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

    // ── happy path ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `promote moves db plus attachments and clears marker`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        File(workDir, "staging.db-wal").writeBytes(byteArrayOf(9))
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "a.bin").writeBytes(byteArrayOf(5, 6))
        File(attStaging, "sub").apply { mkdirs() }.let { File(it, "b.bin").writeBytes(byteArrayOf(7)) }

        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        // P2: db moved (+ wal sidecar) onto real path; staging gone.
        assertTrue(realDb.exists(), "real db must exist after promote")
        assertContentEquals(byteArrayOf(1, 2, 3, 4), realDb.readBytes())
        assertTrue(File(realDb.absolutePath + "-wal").exists(), "-wal sidecar must move too")
        assertFalse(stagingDb.exists(), "staging db must be moved away")

        // P3: attachments moved (files + nested dir).
        assertContentEquals(byteArrayOf(5, 6), File(realAtt, "a.bin").readBytes())
        assertContentEquals(byteArrayOf(7), File(realAtt, "sub/b.bin").readBytes())

        // P1: key installed exactly once.
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }

        // P4: marker cleared.
        assertFalse(markerStore.exists(), "marker must be cleared at P4")
    }

    @Test
    fun `promote removes the live db's stale sidecars before the staging db lands`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0)) }
        // What a once-opened live DB leaves behind: an old WAL, its shm index, and WCDB repair materials.
        val stale = listOf("-wal", "-shm", "-first.material", "-last.material", "-incremental.material")
            .map { File(realDb.absolutePath + it).apply { writeBytes(byteArrayOf(0x55)) } }

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )

        assertContentEquals(byteArrayOf(1, 2, 3, 4), realDb.readBytes())
        stale.forEach { assertFalse(it.exists(), "${it.name} from the old live db must not survive the promote") }
    }

    // ── debug-plaintext build: null staging key → no key install (mirrors WCDB.kt debug) ────────

    @Test
    fun `promote with a null staging key skips key install and still moves db (plaintext debug)`() = runTest {
        // Debug build: StagingDatabase.create yields a null stagingKey (plaintext), so promote must NOT
        // call installKey — the promoted plaintext DB is opened by a debug WCDB.kt that sets no key.
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "a.bin").writeBytes(byteArrayOf(5))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = null,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        // DB + attachments still promoted; marker cleared.
        assertContentEquals(byteArrayOf(1, 2, 3), realDb.readBytes())
        assertContentEquals(byteArrayOf(5), File(realAtt, "a.bin").readBytes())
        assertFalse(markerStore.exists(), "marker must be cleared at P4")
        // No key install (plaintext) and no key protect/unprotect calls.
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
        verify(exactly = 0) { WCDBKeyManager.protectKey(any()) }
        verify(exactly = 0) { WCDBKeyManager.unprotectKey(any()) }
    }

    @Test
    fun `resumeIfPending re-does a null-key (debug plaintext) promote without installing a key`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(4, 4)) }
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        // Marker built with a null staging key → protectedStagingKeyB64 is null (no Keystore call).
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = null,
                attachmentsStagingDir = File(workDir, "att-staging").apply { mkdirs() }.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, step.resumeIfPending())
        assertContentEquals(byteArrayOf(4, 4), realDb.readBytes())
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    // ── net-install precondition is the CALLER's (promote does not re-check) ────────────────────

    @Test
    fun `promote does not re-assert net-install and proceeds over a pre-existing real db (caller asserted)`() = runTest {
        // The orchestrator's row-count guard owns the net-install check; promote runs on FILES and
        // cannot distinguish a schema-only fresh DB (~90 KB, 0 rows) from data by length. So promote
        // must NOT re-reject a real-db path that already has bytes — it promotes (P2 rename overwrites).
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val realDb = File(workDir, "tt_wcdb_database.db").apply { writeBytes(byteArrayOf(42)) } // schema-only stand-in

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = File(workDir, "att").absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )
        // P2 moved the staging db onto the real path (no false-reject); marker cleared at P4.
        assertContentEquals(byteArrayOf(1), realDb.readBytes())
        assertFalse(stagingDb.exists())
        assertFalse(markerStore.exists())
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }
    }

    @Test
    fun `promote allows a zero-byte real db (net-install)`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val realDb = File(workDir, "tt_wcdb_database.db").apply { createNewFile() } // 0 bytes

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = File(workDir, "att").absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = File(workDir, "real-att").absolutePath,
        )
        assertContentEquals(byteArrayOf(1), realDb.readBytes())
        assertFalse(markerStore.exists())
    }

    // ── resume / crash recovery (§5) ────────────────────────────────────────────────────────

    @Test
    fun `resumeIfPending returns NothingToResume when no marker`() = runTest {
        assertEquals(PromoteStep.ResumeResult.NothingToResume, step.resumeIfPending())
    }

    @Test
    fun `resumeIfPending re-does P1 to P4 from a surviving marker`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(8, 8)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "c.bin").writeBytes(byteArrayOf(3))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att")

        // Simulate a crash AFTER P0 (marker written) but BEFORE the moves.
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, resumed)
        assertContentEquals(byteArrayOf(8, 8), realDb.readBytes())
        assertContentEquals(byteArrayOf(3), File(realAtt, "c.bin").readBytes())
        verify(exactly = 1) { WCDBKeyManager.installKey(context, stagingKey) }
        assertFalse(markerStore.exists(), "marker must be cleared after a successful resume")
    }

    @Test
    fun `resumeIfPending discards a half-promote when all staging products are gone`() = runTest {
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); createNewFile() // 0 bytes, empty net-install state
        }
        // Marker points at staging products that no longer exist (db AND attachment dir both gone).
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "gone-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = File(workDir, "gone-att").absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = File(workDir, "real-att").absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        // Fix 4: a pending-but-unfinishable promote is DISCARDED to clean state (NOT ResumedWithData).
        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, resumed)
        // Net-install invariant: empty real DB left untouched, stale marker cleared, no key install.
        assertEquals(0L, realDb.length())
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    @Test
    fun `resumeIfPending discards on staging gone even when a schema-only real db is present (no byte-size check)`() = runTest {
        // REGRESSION: the discard decision must NOT hinge on the real DB's byte size. A fresh
        // net-install already has a ~90 KB schema-only real DB; the old `realDb.length()==0L` check
        // would (wrongly) treat that as "real DB has data → redo" and promote nothing meaningfully.
        // With the DB-free staging-product signal, both staging products gone → discard regardless of
        // the real DB's size.
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(ByteArray(90112)) // ~90 KB schema-only stand-in (non-empty file)
        }
        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "gone-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = File(workDir, "gone-att").absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = File(workDir, "real-att").absolutePath,
            )
        )

        val resumed = step.resumeIfPending()

        // Staging products gone → discard to clean net-install, NOT a phantom ResumedWithData.
        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, resumed)
        assertFalse(markerStore.exists())
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
    }

    @Test
    fun `resumeIfPending finishes P3 when P2 already ran (db present, staging gone)`() = runTest {
        // P2 completed pre-crash: real db present, staging db gone, but attachments still in staging.
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 1))
        }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "d.bin").writeBytes(byteArrayOf(4))
        val realAtt = File(workDir, "real-att")

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "already-moved-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, step.resumeIfPending())
        // Real db kept; remaining attachment moved; marker cleared.
        assertContentEquals(byteArrayOf(1, 1), realDb.readBytes())
        assertContentEquals(byteArrayOf(4), File(realAtt, "d.bin").readBytes())
        assertFalse(markerStore.exists())
    }

    @Test
    fun `P3 attachment move is idempotent (already-present dst is not overwritten)`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "e.bin").writeBytes(byteArrayOf(99)) // staging copy
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att").apply { mkdirs() }
        File(realAtt, "e.bin").writeBytes(byteArrayOf(7)) // already-moved copy from prior run

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )
        // The already-present real file wins (not clobbered by the staging copy); staging dropped.
        assertContentEquals(byteArrayOf(7), File(realAtt, "e.bin").readBytes())
        assertFalse(File(attStaging, "e.bin").exists())
    }

    @Test
    fun `P3 preserves canonical owner-dir layout and is per-file idempotent within an owner dir`() = runTest {
        // The importer now stages bytes under the canonical attachment/<ownerKey>/<fileName> layout
        // (this fix). P3 must land them at real/attachment/<ownerKey>/<fileName> AND, on crash-resume,
        // move only the files still in staging within an owner dir that is partly already-moved — never
        // wholesale-deleting an owner dir that still has un-moved children.
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        // One owner dir (a forward authorityId) holding TWO files; one already moved to real.
        val ownerDir = File(attStaging, "5566778899").apply { mkdirs() }
        File(ownerDir, "fwd.jpg").writeBytes(byteArrayOf(10, 11)) // still in staging → must move
        File(ownerDir, "second.jpg").writeBytes(byteArrayOf(12))  // staging copy of an already-moved file
        // A second owner dir (a message id) with one file.
        File(attStaging, "m-image").apply { mkdirs() }.let { File(it, "photo.jpg").writeBytes(byteArrayOf(20)) }

        val realDb = File(workDir, "real/tt_wcdb_database.db")
        val realAtt = File(workDir, "real-att").apply { mkdirs() }
        // Simulate a prior partial promote: second.jpg already at the real owner dir (different bytes).
        File(realAtt, "5566778899").apply { mkdirs() }.let { File(it, "second.jpg").writeBytes(byteArrayOf(77)) }

        step.promote(
            stagingDbPath = stagingDb.absolutePath,
            stagingKey = stagingKey,
            attachmentsStagingDir = attStaging.absolutePath,
            realDbPath = realDb.absolutePath,
            realAttachmentDir = realAtt.absolutePath,
        )

        // Canonical nested layout landed: attachment/<ownerKey>/<fileName>.
        assertContentEquals(byteArrayOf(10, 11), File(realAtt, "5566778899/fwd.jpg").readBytes())
        assertContentEquals(byteArrayOf(20), File(realAtt, "m-image/photo.jpg").readBytes())
        // Per-file idempotency within the owner dir: the already-present real file wins, staging dropped
        // — NOT wholesale-deleted along with the un-moved sibling (which DID move).
        assertContentEquals(byteArrayOf(77), File(realAtt, "5566778899/second.jpg").readBytes())
        assertFalse(File(ownerDir, "fwd.jpg").exists(), "moved staging file removed")
        assertFalse(File(ownerDir, "second.jpg").exists(), "already-present staging copy dropped")
        assertFalse(markerStore.exists(), "marker cleared at P4")
    }

    // ── Fix 1: P3 attachment move failure must NOT be swallowed (data loss) ─────────────────────

    @Test
    fun `P3 attachment move failure throws and keeps marker, then resume retries and completes`() = runTest {
        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(1, 2)) }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "f.bin").writeBytes(byteArrayOf(55))
        val realDb = File(workDir, "real/tt_wcdb_database.db")
        // Block P3: make the real attachment path an existing *regular file* so mkdirs() + writing a
        // child under it both fail → atomicMove(rename) fails AND copyTo throws → atomicMove returns
        // false → P3 must throw (NOT swallow + reach P4).
        val realAtt = File(workDir, "real-att-blocker").apply { writeBytes(byteArrayOf(0)) }

        assertFailsWith<MigrationException.RestoreFailed> {
            step.promote(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        }

        // Marker still present (data-safety): P4 was NOT reached, so cold start will retry P3.
        assertTrue(markerStore.exists(), "marker must survive a P3 attachment-move failure")
        // P2 already ran (db moved); the attachment is still in staging, NOT lost.
        assertTrue(realDb.exists(), "P2 db move happened before the P3 failure")
        assertTrue(File(attStaging, "f.bin").exists(), "attachment must remain in staging (not lost)")

        // Clear the blocker → resumeIfPending must retry P3 idempotently and complete.
        realAtt.delete()
        val resumed = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.ResumedWithData, resumed)
        assertContentEquals(byteArrayOf(55), File(realAtt, "f.bin").readBytes())
        assertFalse(markerStore.exists(), "marker cleared once P3 succeeds on retry")
    }

    // ── Fix 1: listFiles() == null on an existing staging dir must throw (keep marker) ──────────

    @Test
    fun `P3 listFiles null on existing staging dir throws and keeps marker`() = runTest {
        // Post-P2 recovery state: staging db gone (P2 ran), real db present, attachment dir still here.
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 1))
        }
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "h.bin").writeBytes(byteArrayOf(3))
        val realAtt = File(workDir, "real-att")

        // Force the I/O-error shape: an EXISTING staging dir whose listFiles() returns null. A healthy
        // temp dir cannot reproduce that on the JVM, so inject a dirLister that returns null for the
        // staging dir (exists() stays real → true). This drives the REAL moveAttachments null-branch.
        val stepWithNullLister = PromoteStep(
            context, markerStore, UnconfinedTestDispatcher(),
            dirLister = { dir -> if (dir.absolutePath == attStaging.absolutePath) null else dir.listFiles() },
        )

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = File(workDir, "already-moved-staging.db").absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        assertFailsWith<MigrationException.RestoreFailed> {
            stepWithNullLister.resumeIfPending()
        }

        // Marker survives → cold start retries P3 (attachments NOT silently skipped + lost).
        assertTrue(markerStore.exists(), "marker must survive a listFiles()==null I/O error")
        assertTrue(File(attStaging, "h.bin").exists(), "attachment must remain in staging (not lost)")
    }

    // ── Fix 2: unrecoverable staging key → clear marker + discard staging, no loop ──────────────

    @Test
    fun `resumeIfPending aborts to clean state when staging key is unrecoverable`() = runTest {
        // unprotectKey throwing WCDBKeyUnavailableException = the Keystore alias is permanently gone:
        // the promote can NEVER complete. Safe-abort: clear marker + discard staging, distinct outcome.
        every { WCDBKeyManager.unprotectKey(any()) } throws
            WCDBKeyUnavailableException("Keystore alias invalidated")

        val stagingDb = File(workDir, "staging.db").apply { writeBytes(byteArrayOf(7, 7)) }
        File(workDir, "staging.db-wal").writeBytes(byteArrayOf(1))
        val attStaging = File(workDir, "att-staging").apply { mkdirs() }
        File(attStaging, "g.bin").writeBytes(byteArrayOf(9))
        val realDb = File(workDir, "real/tt_wcdb_database.db").apply {
            parentFile?.mkdirs(); createNewFile() // empty net-install target
        }
        val realAtt = File(workDir, "real-att")

        markerStore.write(
            PromoteMarker.create(
                stagingDbPath = stagingDb.absolutePath,
                stagingKey = stagingKey,
                attachmentsStagingDir = attStaging.absolutePath,
                realDbPath = realDb.absolutePath,
                realAttachmentDir = realAtt.absolutePath,
            )
        )

        // Distinct outcome: RestoreAbortedCleanState (NOT the retryable RestoreFailed).
        assertFailsWith<MigrationException.RestoreAbortedCleanState> {
            step.resumeIfPending()
        }

        // Marker cleared + staging products discarded → cold start cannot loop forever.
        assertFalse(markerStore.exists(), "marker must be cleared on unrecoverable abort")
        assertFalse(stagingDb.exists(), "staging db discarded on abort")
        assertFalse(File(workDir, "staging.db-wal").exists(), "staging -wal sidecar discarded on abort")
        assertFalse(attStaging.exists(), "staging attachment dir discarded on abort")
        assertEquals(0L, realDb.length(), "empty real DB untouched (net-install invariant)")
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }

        // Idempotent safe-abort: a SECOND resume finds no marker → NothingToResume, no loop, no key calls.
        val second = step.resumeIfPending()
        assertEquals(
            PromoteStep.ResumeResult.NothingToResume, second,
            "no marker remains → nothing to resume (loop is impossible)",
        )
    }

    // ── Fix 7: corrupt marker must NOT be silently ignored forever ──────────────────────────────

    @Test
    fun `resumeIfPending discards to clean state on a corrupt (unparseable) marker`() = runTest {
        // A marker FILE exists but holds garbage that Gson cannot parse into a PromoteMarker. The old
        // behavior treated this as "no marker" → silent no-op every cold start (half-promote stuck
        // forever). Fix 7: it must be cleared + treated as discard-to-clean-state (net-install invariant).
        File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME)
            .writeText("{ this is not valid json for a marker ]]]")

        val result = step.resumeIfPending()

        assertEquals(PromoteStep.ResumeResult.DiscardedToCleanState, result)
        // Corrupt marker cleared → cold start cannot loop on it forever.
        assertFalse(markerStore.exists(), "corrupt marker must be cleared")
        verify(exactly = 0) { WCDBKeyManager.installKey(any(), any()) }
        // A second pass finds nothing → no loop.
        assertEquals(PromoteStep.ResumeResult.NothingToResume, step.resumeIfPending())
    }
}

/**
 * Pure-JVM tests for [PromoteMarkerStore] atomic write/read/clear. No Keystore — the marker holds an
 * already-protected Base64 blob, so these tests construct [PromoteMarker] directly (bypassing
 * [PromoteMarker.create]'s Keystore call).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PromoteMarkerStoreTest {

    private lateinit var context: Context
    private lateinit var store: PromoteMarkerStore

    private fun sampleMarker() = PromoteMarker(
        stagingDbPath = "/data/staging.db",
        protectedStagingKeyB64 = "QUJDREVG",
        attachmentsStagingDir = "/data/att-staging",
        realDbPath = "/data/tt_wcdb_database.db",
        realAttachmentDir = "/data/attachment",
    )

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        store = PromoteMarkerStore(context)
        store.clear()
    }

    @After
    fun teardown() {
        store.clear()
    }

    @Test
    fun `write then read round-trips every field`() {
        assertFalse(store.exists())
        val m = sampleMarker()
        store.write(m)

        assertTrue(store.exists())
        val read = store.read()
        assertEquals(m, read)
    }

    @Test
    fun `clear removes the marker`() {
        store.write(sampleMarker())
        assertTrue(store.exists())
        store.clear()
        assertFalse(store.exists())
        assertEquals(null, store.read())
    }

    @Test
    fun `read returns null when no marker on disk`() {
        assertEquals(null, store.read())
    }

    @Test
    fun `write leaves no temp file behind`() {
        store.write(sampleMarker())
        val tmp = File(context.filesDir, PromoteMarkerStore.MARKER_TMP_NAME)
        assertFalse(tmp.exists(), "atomic write must not leave a .tmp file after success")
    }

    // ── Fix 7: readChecked distinguishes Absent / Present / Corrupt ─────────────────────────────

    @Test
    fun `readChecked returns Absent when no marker on disk`() {
        assertEquals(PromoteMarkerStore.ReadResult.Absent, store.readChecked())
    }

    @Test
    fun `readChecked returns Present for a valid marker`() {
        val m = sampleMarker()
        store.write(m)
        assertEquals(PromoteMarkerStore.ReadResult.Present(m), store.readChecked())
    }

    @Test
    fun `readChecked returns Corrupt for an unparseable marker file`() {
        File(context.filesDir, PromoteMarkerStore.MARKER_FILE_NAME).writeText("not-json ]]]")
        assertEquals(PromoteMarkerStore.ReadResult.Corrupt, store.readChecked())
        // The legacy read() still returns null for the corrupt case (back-compat).
        assertEquals(null, store.read())
    }

    // ── debug-plaintext build: a null staging key produces a null protected blob ────────────────

    @Test
    fun `create with a null staging key yields a null protected blob and stagingKey returns null`() {
        // Debug build (plaintext staging DB): no cipher key → no Keystore protect call. This branch is
        // pure JVM (the `?.let` short-circuits before WCDBKeyManager.protectKey), so it runs here.
        val marker = PromoteMarker.create(
            stagingDbPath = "/data/staging.db",
            stagingKey = null,
            attachmentsStagingDir = "/data/att",
            realDbPath = "/data/tt_wcdb_database.db",
            realAttachmentDir = "/data/attachment",
        )
        assertEquals(null, marker.protectedStagingKeyB64, "null staging key must persist as null (no protect)")
        assertEquals(null, marker.stagingKey(), "stagingKey() must return null when no key was protected")

        // And it round-trips through the store unchanged (Gson serializes the null field).
        store.write(marker)
        assertEquals(marker, store.read())
    }
}
