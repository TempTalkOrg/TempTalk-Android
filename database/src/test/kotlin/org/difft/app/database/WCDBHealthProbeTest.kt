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

    

    @Test
    fun `dbCorrupted defaults to false`() {
        assertFalse(wcdb.dbCorrupted, "Fresh WCDB must not be marked corrupt")
    }

    @Test
    fun `markCorrupted flips dbCorrupted to true`() {
        wcdb.markCorrupted()
        assertTrue(wcdb.dbCorrupted, "markCorrupted() must set the flag (RACE-2 pre-close path)")
    }

    





    @Test
    fun `T15b probeHealthy short-circuits to CORRUPT when already marked, without opening db`() {
        wcdb.markCorrupted()
        val result = wcdb.probeHealthy()
        assertEquals(DbHealth.CORRUPT, result, "A known-corrupt DB must not be re-probed (RACE-3)")
    }

    

    


    @Test
    @Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation")
    fun `T15 probeHealthy returns HEALTHY for a valid empty db and leaves flag false`() {
        
        wcdb.db.execute("PRAGMA journal_mode")
        val result = wcdb.probeHealthy()
        assertEquals(DbHealth.HEALTHY, result)
        assertFalse(wcdb.dbCorrupted)
    }

    



    @Test
    @Ignore("WCDB native library not loadable in JVM unit tests; run via instrumentation")
    fun `T14 probeHealthy returns CORRUPT for a header-smashed db and sets the flag`() {
        
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
