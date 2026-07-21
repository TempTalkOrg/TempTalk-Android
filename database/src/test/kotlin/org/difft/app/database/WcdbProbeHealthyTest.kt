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

/**
 * Tri-state probe tests for [probeHealthy] (WCDBHealthProbe.kt).
 *
 * Runnable without native WCDB: the KEY_UNAVAILABLE / CORRUPT short-circuits return before touching
 * `db`, so no `System.loadLibrary`. The full open-and-throw classification paths (`db.execute`
 * raising a real exception) need a real native WCDB / release build; the DEBUG-independent
 * [WCDB.resolveCipherKeyOnce] key-resolution seam is covered by [WcdbResolveCipherKeyTest].
 */
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

    /**
     * Once a key failure has flipped [WCDB.keyUnavailable], [probeHealthy] returns
     * KEY_UNAVAILABLE without opening `db`, never sets dbCorrupted, and never records corruption.
     */
    @Test
    fun `T1 probeHealthy short-circuits to KEY_UNAVAILABLE after a key failure, without opening db`() {
        every { WCDBKeyManager.getOrCreateKey(any()) } throws
            WCDBKeyUnavailableException("boom", IllegalStateException("keystore down"))
        val wcdb = WCDB(ctx, TestScope())
        // Flip keyUnavailable via the choke point (resolveCipherKeyOnce), then probe.
        assertFailsWith<WCDBKeyUnavailableException> { wcdb.resolveCipherKeyOnce() }

        val result = wcdb.probeHealthy()

        assertEquals(DbHealth.KEY_UNAVAILABLE, result, "key failure must route to KEY_UNAVAILABLE")
        assertTrue(wcdb.keyUnavailable)
        assertFalse(wcdb.dbCorrupted, "key failure must NEVER set dbCorrupted (no wipe)")
    }

    /** probeHealthy short-circuits to CORRUPT when the DB was already marked corrupt, without re-opening `db`. */
    @Test
    fun `probeHealthy short-circuits to CORRUPT when already marked, without opening db`() {
        val wcdb = WCDB(ctx, TestScope())
        wcdb.markCorrupted()
        assertEquals(DbHealth.CORRUPT, wcdb.probeHealthy())
    }

    /**
     * When BOTH keyUnavailable and dbCorrupted are set (key fails mid-corruption-recovery),
     * probeHealthy must prefer KEY_UNAVAILABLE over CORRUPT: a wipe can't succeed without the key,
     * so fail-soft wins rather than routing to the destructive corruption path.
     */
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
}
