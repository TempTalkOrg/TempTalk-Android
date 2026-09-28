package org.difft.app.database

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.difft.android.base.log.WCDBKeyUnavailableException
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

    // --------------------------------------------------- cipherKeyForCurrentBuild (build-gated)

    /**
     * The migration engine mirrors `WCDB.kt`'s encryption policy via [WCDBKeyManager.cipherKeyForCurrentBuild]:
     * a real key in release, `null` (plaintext) in debug. Unit tests run under the `debug` build type
     * (`BuildConfig.DEBUG == true`), so this MUST return `null` — and it does so WITHOUT touching the
     * AndroidKeystore (the `!BuildConfig.DEBUG` short-circuits before `getOrCreateKey`), which is why
     * this is testable on the host JVM where the release branch is not. This pins the debug-plaintext
     * half of the on-device fix: snapshot read / staging / promote all open plaintext in debug.
     */
    @Test
    fun `cipherKeyForCurrentBuild returns null in debug build (plaintext, no keystore call)`() {
        assertEquals(
            null,
            WCDBKeyManager.cipherKeyForCurrentBuild(context),
            "debug build must use NO cipher key (plaintext), mirroring WCDB.kt's !BuildConfig.DEBUG gate",
        )
        // It must not have created wcdb_key.bin as a side effect (no getOrCreateKey in the debug branch).
        assertEquals(
            false, keyFile.exists(),
            "debug cipherKeyForCurrentBuild must not generate/persist a key",
        )
    }

    // --------------------------------------------------------------------- installKey

    /**
     * `installKey` validates the 48-byte length BEFORE any Keystore call (the `require` runs
     * first in the method body), so a wrong-length key throws `IllegalArgumentException` on the
     * host JVM without needing the AndroidKeystore. This pins the A4 promote contract: only a
     * full-length WCDB cipher key may be installed as `wcdb_key.bin`.
     */
    @Test
    fun `installKey rejects a key that is not 48 bytes`() {
        try {
            WCDBKeyManager.installKey(context, ByteArray(32))
            fail("Expected IllegalArgumentException for a 32-byte key")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("48"),
                "Error must state the required 48-byte length; was: '${e.message}'"
            )
        }
        // No file must have been written on a rejected install.
        assertEquals(false, keyFile.exists(), "installKey must not write wcdb_key.bin on length rejection")
    }

    /**
     * `protectKey` likewise validates length before touching the Keystore.
     */
    @Test
    fun `protectKey rejects a key that is not 48 bytes`() {
        try {
            WCDBKeyManager.protectKey(ByteArray(10))
            fail("Expected IllegalArgumentException for a 10-byte key")
        } catch (e: IllegalArgumentException) {
            assertNotNull(e.message)
            assertTrue(e.message!!.contains("48"), "Error must state the required 48-byte length")
        }
    }

    /**
     * `unprotectKey` rejects a wrong-size blob before any Keystore call (the size-check is the
     * first statement, mirroring `readAndDecrypt`). Runs on the host JVM.
     */
    @Test
    fun `unprotectKey rejects a blob that is not the 76-byte wire size`() {
        try {
            WCDBKeyManager.unprotectKey(ByteArray(40))
            fail("Expected WCDBKeyUnavailableException for a 40-byte blob")
        } catch (e: WCDBKeyUnavailableException) {
            assertNotNull(e.message)
            assertTrue(
                e.message!!.contains("76") || e.message!!.contains("expected"),
                "Error must indicate the wire-format size mismatch; was: '${e.message}'"
            )
        }
    }

    // --------------------------------------------------------------------- @Ignore'd
    // The tests below require the AndroidKeystore (AES key generation) which is
    // not emulated by Robolectric. They document the contract for future
    // integration / instrumentation tests.

    /**
     * Promote contract: installing a key then reading it back via `getOrCreateKey` (which decrypts
     * the same `wcdb_key.bin`) returns the exact bytes installed. Requires the AndroidKeystore for
     * the AES-GCM round trip, so `@Ignore`'d on the host JVM (same limitation as the tests below).
     */
    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `installKey then getOrCreateKey returns the installed bytes`() {
        val key = ByteArray(48) { (it * 7 + 1).toByte() }
        WCDBKeyManager.installKey(context, key)
        val readBack = WCDBKeyManager.getOrCreateKey(context)
        assertTrue(key.contentEquals(readBack), "getOrCreateKey must return the bytes installKey wrote")
        assertEquals(76, keyFile.length(), "installKey must produce the 76-byte wire format")
    }

    /**
     * `protectKey` / `unprotectKey` round-trip returns the original key. Requires the AndroidKeystore.
     */
    @Test
    @Ignore("Requires AndroidKeystore — not available in Robolectric (host JVM)")
    fun `protectKey then unprotectKey returns the original key`() {
        val key = ByteArray(48) { (it xor 0x5A).toByte() }
        val blob = WCDBKeyManager.protectKey(key)
        assertEquals(76, blob.size, "protected blob must be the 76-byte wire format")
        val recovered = WCDBKeyManager.unprotectKey(blob)
        assertTrue(key.contentEquals(recovered), "unprotectKey must return the protected bytes")
    }

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
