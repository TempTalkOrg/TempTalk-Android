package com.difft.android.base.glide

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Behavioral guardrails for [GlideCacheKeyManager] — the degradation + self-heal contract.
 *
 * Robolectric does not emulate the AndroidKeystore (same limitation documented in
 * `WCDBKeyManagerTest`), so on the host JVM the Keystore-backed encrypt/decrypt path is unavailable
 * and [GlideCacheKeyManager.getKeyOrNull] degrades to `null`. The runnable tests below therefore pin
 * the **environment-independent** invariants that matter most for safety:
 *
 *  - it NEVER throws (the encrypted cache is a disposable optimization, never a correctness
 *    dependency) — neither on a clean slate, nor on a corrupt/zero/oversize/undecryptable key file;
 *  - its result is always either `null` or exactly a 32-byte key;
 *  - `getKeyOrNull()` and `isAvailable()` always agree;
 *  - repeated calls are stable.
 *
 * The Keystore-dependent specifics (actual key generation, self-heal SUCCESS, round-trip stability)
 * require a real device/emulator and are documented as `@Ignore`d contract tests, mirroring
 * `WCDBKeyManagerTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GlideCacheKeyManagerTest {

    private lateinit var context: Context

    private val keyFile: File
        get() = File(context.filesDir, "glide_cache_key.bin")

    private val tempFile: File
        get() = File(context.filesDir, "glide_cache_key.bin.tmp")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        resetCachedKey()
        // Robolectric reuses filesDir across tests in the same process — start clean.
        keyFile.delete()
        tempFile.delete()
    }

    @After
    fun teardown() {
        resetCachedKey()
        keyFile.delete()
        tempFile.delete()
    }

    /** Reset the object's in-memory cache + readiness state so each test observes a cold load. */
    private fun resetCachedKey() {
        GlideCacheKeyManager::class.java.getDeclaredField("cached").apply {
            isAccessible = true
            set(GlideCacheKeyManager, null)
        }
        GlideCacheKeyManager::class.java.getDeclaredField("warmState").apply {
            isAccessible = true
            setInt(GlideCacheKeyManager, 0) // STATE_UNKNOWN
        }
        // Clear the in-flight guard so an async warmUp() launched by a prior test cannot leave
        // `warming` latched and starve warmUp() in the next test.
        GlideCacheKeyManager::class.java.getDeclaredField("warming").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (get(GlideCacheKeyManager) as java.util.concurrent.atomic.AtomicBoolean).set(false)
        }
    }

    private fun assertNullOr32Bytes(key: ByteArray?) {
        assertTrue("key must be null or exactly 32 bytes, was ${key?.size}", key == null || key.size == 32)
    }

    @Test
    fun `getKeyOrNull degrades gracefully without throwing on a clean slate`() {
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
        assertEquals(key != null, GlideCacheKeyManager.isAvailable(context))
    }

    @Test
    fun `getKeyOrNull does not crash on a too-small key file`() {
        keyFile.writeBytes(ByteArray(10))
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on a zero-byte key file`() {
        keyFile.createNewFile()
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on an oversize key file`() {
        keyFile.writeBytes(ByteArray(200))
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `getKeyOrNull does not crash on a correct-size but undecryptable key file`() {
        // 60 bytes = 12 IV + 32 key + 16 tag → passes the size check, fails decryption.
        // This is the exact trigger for the self-heal branch in loadOrCreate().
        keyFile.writeBytes(ByteArray(60) { it.toByte() })
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertNullOr32Bytes(key)
    }

    @Test
    fun `repeated calls return a stable result`() {
        val first = GlideCacheKeyManager.getKeyOrNull(context)
        val second = GlideCacheKeyManager.getKeyOrNull(context)
        if (first == null) {
            assertNull("result must stay null while the environment is unchanged", second)
        } else {
            assertTrue("cached key must be stable across calls", first.contentEquals(second!!))
        }
    }

    @Test
    fun `isAvailable never throws regardless of key file state`() {
        keyFile.writeBytes(ByteArray(3))
        // Must not throw; result type is the only contract on the host JVM.
        GlideCacheKeyManager.isAvailable(context)
    }

    @Test
    fun `isCacheKeyReady returns false on a clean slate without blocking`() {
        // warmState == UNKNOWN → must return false immediately (offloading the keystore work to an
        // async warmUp), never touching the keystore synchronously on the (main-thread) caller.
        assertFalse(GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    fun `isCacheKeyReady agrees with getKeyOrNull once resolved`() {
        // Resolve state via the blocking path first. On the host JVM the Keystore is unavailable, so
        // getKeyOrNull degrades to null → warmState becomes UNAVAILABLE → isCacheKeyReady must be false
        // and consistent with getKeyOrNull(), never throwing.
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(key != null, GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    fun `isCacheKeyReady never throws regardless of key file state`() {
        keyFile.writeBytes(ByteArray(3))
        // Must not throw; the non-blocking UI contract is the only thing pinned on the host JVM.
        GlideCacheKeyManager.isCacheKeyReady(context)
    }

    // --------------------------------------------------------------------- @Ignore'd
    // Keystore-dependent contracts. AndroidKeystore is not emulated by Robolectric, so these
    // document the behavior verified on a real device/emulator (mirrors WCDBKeyManagerTest).

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getKeyOrNull returns a stable 32-byte key and persists a 60-byte file`() {
        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(32, key!!.size)
        assertTrue(keyFile.exists())
        assertEquals(60L, keyFile.length()) // 12 IV + 32 key + 16 GCM tag

        resetCachedKey()
        val reloaded = GlideCacheKeyManager.getKeyOrNull(context)
        assertTrue("key must round-trip from disk", key.contentEquals(reloaded!!))
        assertTrue("no temp file should survive", !tempFile.exists())
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `isCacheKeyReady becomes true after the key resolves`() {
        // On a real device getKeyOrNull() succeeds → warmState = STATE_READY, so the non-blocking UI
        // check flips to true and stays consistent with getKeyOrNull().
        assertEquals(32, GlideCacheKeyManager.getKeyOrNull(context)!!.size)
        assertTrue("isCacheKeyReady must be true once the key resolves", GlideCacheKeyManager.isCacheKeyReady(context))
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `self-heal regenerates a usable key when the key file is unreadable`() {
        // Seed a correct-size but undecryptable file → self-heal must regenerate, not stay disabled.
        keyFile.writeBytes(ByteArray(60) { it.toByte() })

        val key = GlideCacheKeyManager.getKeyOrNull(context)
        assertEquals(32, key!!.size)
        assertEquals(60L, keyFile.length()) // overwritten with a valid sealed key

        resetCachedKey()
        val reloaded = GlideCacheKeyManager.getKeyOrNull(context)
        assertTrue("regenerated key must round-trip", key.contentEquals(reloaded!!))
    }
}
