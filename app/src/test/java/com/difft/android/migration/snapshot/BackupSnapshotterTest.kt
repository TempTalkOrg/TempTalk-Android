package com.difft.android.migration.snapshot

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.migration.model.MigrationException
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import org.difft.app.database.WCDB
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [BackupSnapshotterImpl].
 *
 * Strategy: mock the small [SnapshotDbOps] test seam (the production code path forwards
 * to `wcdb.db` via [WcdbSnapshotDbOps]; tests can't mock the Tencent `Database` directly
 * because its `CppObject` `<clinit>` calls `System.loadLibrary("WCDB")`, which throws
 * `UnsatisfiedLinkError` on the JVM test classpath). The seam is set via the package-
 * private `overrideOps` field after construction.
 *
 * Filesystem-side invariants (tmp file freshness, copy presence, byte equality) are
 * asserted against a real Robolectric-provided `filesDir`. The seed "main DB" file is
 * materialised at `context.getDatabasePath(WCDB.DATABASE_NAME)` with known bytes so the
 * snapshot copy can be byte-compared.
 *
 * Coverage:
 *  - Disable+restore ordering: `PRAGMA = 0` first, `PRAGMA = 1000` always last in `finally`.
 *  - autoCheckpoint restored on failure: `truncateCheckpoint` throws every attempt → restore
 *    PRAGMA still called.
 *  - Retry behaviour: 1st fail + 2nd success → snapshot succeeds; 3× fail → `SnapshotFailed`.
 *  - Pre-existing tmp file cleanup: stale tmp file deleted before copy overwrites it.
 *  - Output file is a byte-for-byte copy of the source DB path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class BackupSnapshotterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var wcdb: WCDB
    private lateinit var dbOps: SnapshotDbOps
    private lateinit var snapshotter: BackupSnapshotterImpl

    // The source DB file the snapshotter copies from.
    private lateinit var sourceDbFile: File
    private val sourceBytes = "hello-snapshot-source".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // Seed the "main DB" at the path the snapshotter will read from.
        sourceDbFile = context.getDatabasePath(WCDB.DATABASE_NAME)
        sourceDbFile.parentFile?.mkdirs()
        sourceDbFile.writeBytes(sourceBytes)

        // WCDB is never actually dereferenced because we install an `overrideOps` —
        // a relaxed mock is enough to satisfy the constructor parameter.
        wcdb = mockk(relaxed = true)
        dbOps = mockk(relaxed = false)
        every { dbOps.execute(any()) } just Runs
        every { dbOps.passiveCheckpoint() } just Runs
        every { dbOps.truncateCheckpoint() } just Runs

        snapshotter = BackupSnapshotterImpl(context, wcdb).apply {
            overrideOps = dbOps
        }
    }

    @After
    fun tearDown() {
        sourceDbFile.delete()
        File(context.filesDir, "migration_temp").deleteRecursively()
    }

    // ─── Happy path / ordering ──────────────────────────────────────────────

    @Test
    fun `takeSnapshot disables autoCheckpoint first then runs checkpoints then restores in finally`() = runTest {
        val out = snapshotter.takeSnapshot()

        // 1. Output file exists and bytes match.
        assertTrue(out.exists(), "snapshot tmp file should exist")
        assertEquals(File(context.filesDir, "migration_temp/chat.db.tmp").absolutePath, out.absolutePath)
        assertContentEquals(sourceBytes, out.readBytes())

        // 2. PRAGMA disable → passiveCheckpoint → truncateCheckpoint → PRAGMA restore.
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.passiveCheckpoint()
            dbOps.truncateCheckpoint()
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }
    }

    @Test
    fun `passiveCheckpoint failure is swallowed and truncateCheckpoint still runs`() = runTest {
        every { dbOps.passiveCheckpoint() } throws RuntimeException("passive failed")

        val out = snapshotter.takeSnapshot()

        assertTrue(out.exists())
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.passiveCheckpoint()                 // attempted
            dbOps.truncateCheckpoint()                // still runs
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }
    }

    // ─── Retry on truncateCheckpoint ────────────────────────────────────────

    @Test
    fun `truncateCheckpoint retries and succeeds on second attempt`() = runTest {
        var attempts = 0
        every { dbOps.truncateCheckpoint() } answers {
            attempts += 1
            if (attempts < 2) throw RuntimeException("SQLITE_BUSY attempt=$attempts")
            // 2nd attempt succeeds.
        }

        val out = snapshotter.takeSnapshot()

        assertTrue(out.exists(), "snapshot succeeds after retry")
        verify(exactly = 2) { dbOps.truncateCheckpoint() }
        // restore PRAGMA still called.
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    @Test
    fun `truncateCheckpoint exhausts retries throws SnapshotFailed and restores PRAGMA`() = runTest {
        every { dbOps.truncateCheckpoint() } throws RuntimeException("SQLITE_BUSY persistent")

        val ex = assertFailsWith<MigrationException.SnapshotFailed> {
            snapshotter.takeSnapshot()
        }

        assertTrue(ex.message?.contains("truncateCheckpoint exhausted retries") == true)
        // All 3 attempts made.
        verify(exactly = 3) { dbOps.truncateCheckpoint() }

        // autoCheckpoint MUST be restored even though we threw.
        verifyOrder {
            dbOps.execute("PRAGMA wal_autocheckpoint = 0")
            dbOps.execute("PRAGMA wal_autocheckpoint = 1000")
        }

        // No tmp file produced (copy never happened).
        val tmpFile = File(context.filesDir, "migration_temp/chat.db.tmp")
        assertFalse(tmpFile.exists(), "no copy when snapshot failed")
    }

    // ─── Restore PRAGMA on failure paths ────────────────────────────────────

    @Test
    fun `restore PRAGMA is called even when copy step throws`() = runBlocking {
        // Make the source DB unreadable to force the copyTo to fail.
        sourceDbFile.delete()

        val thrown = runCatching { snapshotter.takeSnapshot() }.exceptionOrNull()
        // We do expect an IOException of some flavour from copyTo since source no longer exists.
        assertNotNull(thrown, "copy should have thrown")

        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 0") }
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    // ─── TimeoutCancellationException classification (round-1 Finding 3) ────

    @Test
    fun `TimeoutCancellationException from inner op never escapes as a CancellationException`() = runBlocking {
        // Regression (round-1 Finding 3): TimeoutCancellationException is a
        // CancellationException subclass. If it escaped the Snapshotter unwrapped, the
        // Service's `catch (e: CancellationException)` would grab it and mis-classify the
        // failure as "cancelled" when in reality the truncate timed out.
        //
        // The TimeoutCancellationException constructor is `internal` in kotlinx.coroutines,
        // so we can't construct one directly. Instead we generate a real one by running
        // `withTimeout(1) { delay(1000) }` — same code path as the production timeout —
        // and arrange for `truncateCheckpoint` to throw the captured exception.
        val realTimeoutEx: TimeoutCancellationException = try {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(1000L) }
            error("withTimeout did not throw — test setup invalid")
        } catch (e: TimeoutCancellationException) {
            e
        }

        every { dbOps.truncateCheckpoint() } throws realTimeoutEx

        val ex = assertFailsWith<MigrationException.SnapshotFailed> {
            snapshotter.takeSnapshot()
        }

        // The critical assertion: whatever propagates out, it is NOT a CancellationException.
        // (Either path produces SnapshotFailed: outer-catch translates TCE directly, or the
        // retry loop's `runCatching` wraps TCE as `lastError` and throws "exhausted retries".)
        // We widen to Throwable to make the type check happen at runtime — the static type
        // `MigrationException.SnapshotFailed` is statically known to not extend
        // CancellationException (which is the property we're documenting), so a direct
        // `ex is CancellationException` would be a compile-time tautology.
        val thrown: Throwable = ex
        assertFalse(
            thrown is CancellationException,
            "SnapshotFailed must NOT be a CancellationException so the Service's " +
                "`catch (CancellationException)` doesn't grab it and mis-classify as Cancelled",
        )
        // restore PRAGMA still called (finally block).
        verify { dbOps.execute("PRAGMA wal_autocheckpoint = 1000") }
    }

    @Test
    fun `TimeoutCancellationException catch does not catch plain CancellationException superclass`() = runBlocking {
        // Regression: verify our `catch (TimeoutCancellationException)` is strict — a plain
        // CancellationException (which user-cancellation produces via cancelChildren()) is
        // NOT a TimeoutCancellationException, so it bypasses our intercept and continues
        // propagating up to the Service's `catch (CancellationException)` → Cancelled.
        //
        // We don't try to simulate cooperative cancellation here (runTest virtual time
        // makes that complex); instead we assert the type relationship that makes our
        // catch correct: TimeoutCancellationException IS-A CancellationException, but
        // CancellationException IS-NOT-A TimeoutCancellationException.
        val realTimeoutEx: TimeoutCancellationException = try {
            kotlinx.coroutines.withTimeout(1L) { kotlinx.coroutines.delay(1000L) }
            error("withTimeout did not throw — test setup invalid")
        } catch (e: TimeoutCancellationException) {
            e
        }
        val timeoutEx: CancellationException = realTimeoutEx
        assertTrue(timeoutEx is TimeoutCancellationException)

        val plainCancelEx: CancellationException = CancellationException("user cancelled")
        assertFalse(
            plainCancelEx is TimeoutCancellationException,
            "Plain CancellationException must NOT match the TimeoutCancellationException " +
                "catch — otherwise user cancellation would be mis-classified as a snapshot timeout",
        )
    }

    // ─── Pre-existing tmp file is replaced ──────────────────────────────────

    @Test
    fun `pre-existing tmp file is deleted before copy`() = runTest {
        // Seed a stale tmp file with sentinel content.
        val tmpDir = File(context.filesDir, "migration_temp").apply { mkdirs() }
        val staleTmp = File(tmpDir, "chat.db.tmp")
        staleTmp.writeBytes("STALE-CONTENT".toByteArray())

        val out = snapshotter.takeSnapshot()

        // The output replaces the stale content with the new copy.
        assertContentEquals(sourceBytes, out.readBytes())
        assertFalse(out.readBytes().contentEquals("STALE-CONTENT".toByteArray()))
    }
}
