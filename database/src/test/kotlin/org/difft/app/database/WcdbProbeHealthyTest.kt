package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue









@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WcdbProbeHealthyTest {

    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        mockkObject(WCDBKeyManager)
    }

    @After
    fun tearDown() {
        unmockkObject(WCDBKeyManager)
    }

    



    @Test
    fun `T1 probeHealthy short-circuits to KEY_UNAVAILABLE after a key failure, without opening db`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))
        val wcdb = WCDB(ctx, TestScope())
        
        assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }

        val result = wcdb.probeHealthy()

        assertEquals(DbHealth.KEY_UNAVAILABLE, result, "key failure must route to KEY_UNAVAILABLE")
        assertTrue(wcdb.keyUnavailable)
        assertFalse(wcdb.dbCorrupted, "key failure must NEVER set dbCorrupted (no wipe)")
    }

    
    @Test
    fun `probeHealthy short-circuits to CORRUPT when already marked, without opening db`() {
        val wcdb = WCDB(ctx, TestScope())
        wcdb.markCorrupted()
        assertEquals(DbHealth.CORRUPT, wcdb.probeHealthy())
    }

    




    @Test
    fun `probeHealthy prefers KEY_UNAVAILABLE over CORRUPT when both are set`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))
        val wcdb = WCDB(ctx, TestScope())
        assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }
        wcdb.markCorrupted()

        assertEquals(DbHealth.KEY_UNAVAILABLE, wcdb.probeHealthy(), "key failure must take precedence over corruption")
        assertTrue(wcdb.keyUnavailable)
        assertTrue(wcdb.dbCorrupted)
    }

    




    @Test
    fun `every probe exit marks the probe finished without marking it healthy`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))
        val keyFailed = WCDB(ctx, TestScope())
        assertFailsWith<WCDBKeyUnavailableException> { keyFailed.resolveCipherKeyOnce() }
        val corrupt = WCDB(ctx, TestScope()).apply { markCorrupted() }

        listOf(keyFailed, corrupt).forEach { wcdb ->
            assertFalse(wcdb.probeFinished.value, "not finished before the first probe")
            wcdb.probeHealthy()
            assertTrue(wcdb.probeFinished.value)
            assertFalse(wcdb.isHealthyProbed)
        }
    }
}
