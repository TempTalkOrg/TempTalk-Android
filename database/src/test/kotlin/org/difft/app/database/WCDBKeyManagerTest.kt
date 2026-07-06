package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Behavioral guardrails for [WCDBKeyManager].
 *
 * Pins the contracts the design (§5.3/§5.4/§5.7) calls out as load-bearing:
 *
 *   1. **Truncated/missing-size on-disk file** — short-circuits to
 *      [WCDBKeyUnavailableException] BEFORE any Keystore call (R7 fix: no silent
 *      plaintext fallback).
 *
 * Tests that need real AES key generation (round-trip, atomic write, tampering)
 * are guarded with `@Ignore` because Robolectric does not emulate the
 * AndroidKeystore — same limitation noted in [WCDBJobTableRegistrationTest] for
 * native WCDB libraries. They document the contract for future integration
 * tests on a real device / emulator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WCDBKeyManagerTest {

    private lateinit var context: Context

    private val keyFile: File
        get() = File(context.filesDir, "wcdb_key.bin")

    private val tempFile: File
        get() = File(context.filesDir, "wcdb_key.bin.tmp")

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        // Clean slate — Robolectric reuses filesDir across tests in the same process.
        keyFile.delete()
        tempFile.delete()
    }

    @After
    fun teardown() {
        keyFile.delete()
        tempFile.delete()
    }

    /**
     * R7 fix verification — the size-check happens BEFORE the Keystore lookup, so
     * this exercises real production code on the JVM. A truncated file MUST throw
     * `WCDBKeyUnavailableException`. Critically, it must NOT silently regenerate
     * a fresh key (that would be the R7 silent-downgrade behavior we removed).
     */
    @Test
    fun `truncated key file throws WCDBKeyUnavailableException without keystore call`() {
        // Pre-create a malformed (too-small) key file.
        keyFile.writeBytes(ByteArray(10))

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for truncated key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("Corrupt") || e.message!!.contains("expected"),
                "Error message must indicate the wire-format size mismatch; was: '${e.message}'"
            )
        }
    }

    /**
     * Empty key file (0 bytes) — should also be rejected with WCDBKeyUnavailableException,
     * not be silently treated as "no key exists" (because `keyFile.exists()` is true).
     */
    @Test
    fun `zero-byte key file throws WCDBKeyUnavailableException`() {
        keyFile.createNewFile() // 0 bytes

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for zero-byte key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }

    /**
     * Oversize key file (e.g. corrupted append) — rejected before any decryption call.
     */
    @Test
    fun `oversize key file throws WCDBKeyUnavailableException`() {
        keyFile.writeBytes(ByteArray(200))

        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for oversize key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }

    // --------------------------------------------------------------------- @Ignore'd
    // The tests below require the AndroidKeystore (AES key generation) which is
    // not emulated by Robolectric. They document the contract for future
    // integration / instrumentation tests.

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey returns 48-byte plaintext on first call`() {
        val key = WCDBKeyManager.getOrCreateKey(context)
        assertEquals(48, key.size, "WCDB cipher plaintext must be exactly 48 bytes")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey returns the same key on subsequent calls`() {
        val first = WCDBKeyManager.getOrCreateKey(context)
        val second = WCDBKeyManager.getOrCreateKey(context)
        assertTrue(first.contentEquals(second), "WCDB cipher key must be stable across calls")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey produces a 76-byte wire-format file`() {
        WCDBKeyManager.getOrCreateKey(context)
        assertTrue(keyFile.exists(), "wcdb_key.bin must exist after getOrCreateKey")
        assertEquals(
            76, keyFile.length(),
            "wcdb_key.bin must be exactly 12 IV + 48 ciphertext + 16 tag = 76 bytes"
        )
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `getOrCreateKey leaves no temp file after success`() {
        WCDBKeyManager.getOrCreateKey(context)
        assertEquals(false, tempFile.exists(), "temp file must not survive a successful atomic write")
    }

    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `tampered GCM tag throws WCDBKeyUnavailableException`() {
        WCDBKeyManager.getOrCreateKey(context)
        val bytes = keyFile.readBytes()
        for (i in bytes.size - 16 until bytes.size) {
            bytes[i] = (bytes[i].toInt() xor 0xFF).toByte()
        }
        keyFile.writeBytes(bytes)
        try {
            WCDBKeyManager.getOrCreateKey(context)
            fail("Expected WCDBKeyUnavailableException for tampered key file")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
        }
    }
}
