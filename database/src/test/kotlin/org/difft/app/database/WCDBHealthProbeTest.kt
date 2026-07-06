package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.messageserialization.db.store.TestWcdbFactory
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.RandomAccessFile
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the DB corruption-recovery redesign's probe + flag on [WCDB]
 * (design `tmp/db-recovery-redesign/design.md` §2.2 / §B.2.1).
 *
 * Split into two tiers:
 *  - **Runnable** (no native WCDB): the RACE-3 short-circuit and the [WCDB.dbCorrupted]
 *    flag semantics. These never touch `wcdb.db`, so no native library is loaded.
 *  - **@Ignore-d integration** (real WCDB file): the PRAGMA→exception mapping (T14/T15)
 *    and the ARCH-CRIT-2 retrieve-not-wiped path (T19). WCDB loads native libraries via
 *    `System.loadLibrary`, unavailable to JVM unit tests — same precedent as
 *    [com.difft.android.messageserialization.db.store.DBPublicKeyInfoStoreTest] and
 *    [WCDBJobTableRegistrationTest]. Run via instrumentation/manual verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WCDBHealthProbeTest {

    private lateinit var ctx: Context
    private lateinit var wcdb: WCDB

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        wcdb = TestWcdbFactory.createInMemoryWcdb(ctx)
    }

    // ---------- Runnable: flag semantics + RACE-3 short-circuit (never opens `db`) ----------

    @Test
    fun `dbCorrupted defaults to false`() {
        assertFalse(wcdb.dbCorrupted, "Fresh WCDB must not be marked corrupt")
    }

    @Test
    fun `markCorrupted flips dbCorrupted to true`() {
        wcdb.markCorrupted()
        assertTrue(wcdb.dbCorrupted, "markCorrupted() must set the flag (RACE-2 pre-close path)")
    }

    /**
     * T15b — RACE-3 short-circuit: once [WCDB.markCorrupted] has run, [WCDB.probeHealthy]
     * returns CORRUPT WITHOUT touching `db` (no PRAGMA, no native open). Verified by the
     * fact that this test never loads the native lib yet completes — touching `wcdb.db`
     * would throw an `UnsatisfiedLinkError` on the JVM host.
     */
    @Test
    fun `T15b probeHealthy short-circuits to CORRUPT when already marked, without opening db`() {
        wcdb.markCorrupted()
        val result = wcdb.probeHealthy()
        assertEquals(DbHealth.CORRUPT, result, "A known-corrupt DB must not be re-probed (RACE-3)")
    }

    // ---------- @Ignore-d integration: real WCDB native behavior ----------

    /**
     * T15 — a freshly-created valid (empty) DB probes HEALTHY and leaves the flag false.
     */
    @Test
    @Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation")
    fun `T15 probeHealthy returns HEALTHY for a valid empty db and leaves flag false`() {
        // Force creation of a valid DB by touching a table once.
        wcdb.db.execute("PRAGMA journal_mode")
        val result = wcdb.probeHealthy()
        assertEquals(DbHealth.HEALTHY, result)
        assertFalse(wcdb.dbCorrupted)
    }

    /**
     * T14 — a real header-smashed DB probes CORRUPT and sets the flag. The corruption is
     * created exactly as `WCDB.testCorruptDatabase()` does (16 random bytes at offset 0).
     */
    @Test
    @Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation")
    fun `T14 probeHealthy returns CORRUPT for a header-smashed db and sets the flag`() {
        // Create a valid DB, then smash its header.
        wcdb.db.execute("PRAGMA journal_mode")
        wcdb.db.close()
        val dbFile = ctx.getDatabasePath(WCDB.DATABASE_NAME)
        RandomAccessFile(dbFile, "rw").use { raf ->
            raf.seek(0)
            val randomBytes = ByteArray(16)
            SecureRandom().nextBytes(randomBytes)
            raf.write(randomBytes)
        }
        val result = wcdb.probeHealthy()
        assertEquals(DbHealth.CORRUPT, result)
        assertTrue(wcdb.dbCorrupted)
    }
}
