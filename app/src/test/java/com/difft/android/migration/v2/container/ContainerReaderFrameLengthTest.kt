package com.difft.android.migration.v2.container

import com.difft.android.migration.crypto.BackupCrypto
import com.difft.android.migration.crypto.BackupCryptoImpl
import com.difft.android.migration.model.MigrationException
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import kotlin.test.assertFailsWith

/**
 * Fix 2 — frame-length OOM guard.
 *
 * A 4-byte-length frame (manifest / JSONL record) is fully allocated into a `ByteArray` to be
 * JSON-decoded. A crafted length such as `0x7FFFFFFF` (≈ 2 GiB) would OOM the process before any
 * content is read. [ContainerReaderImpl] must reject any 4-byte length above
 * [ContainerFormat.MAX_FRAME_PAYLOAD_BYTES] as [MigrationException.DecryptionFailed].
 *
 * To exercise the cap on the REAL decrypt path (the check sits AFTER the verify-before-trust HMAC
 * pass), this test hand-builds a container that is genuinely authenticated: it encrypts a manifest
 * frame whose declared length is oversized, then writes a VALID trailing HMAC over `header ‖
 * ciphertext`. So the reader's first pass passes, the second pass decrypts the manifest frame, reads
 * the oversized length, and must reject it BEFORE attempting the giant allocation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContainerReaderFrameLengthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val passphrase = "correct-horse-battery-staple"

    @Test
    fun `oversized 4-byte frame length is rejected as DecryptionFailed (no giant allocation)`() = runTest {
        // Body plaintext = one manifest frame whose 4-byte length is 0x7FFFFFFF (≈ 2 GiB) but with
        // no actual payload bytes following. The cap check must fire on the length alone.
        val bodyPlain = ByteArrayOutputStream().apply {
            write(ContainerFormat.TAG_MANIFEST.toInt())
            write(beInt(Int.MAX_VALUE)) // 0x7FFFFFFF — far above MAX_FRAME_PAYLOAD_BYTES
        }.toByteArray()

        val container = craftAuthenticatedContainer(bodyPlain)

        val reader = ContainerReaderImpl(crypto)
        val ex = assertFailsWith<MigrationException.DecryptionFailed> {
            reader.peekManifest(container, passphrase)
        }
        // Sanity: it failed on the length cap, not on a different structural error.
        assert(ex.message?.contains("exceeds max") == true) {
            "expected the frame-length cap to reject it, got: ${ex.message}"
        }
    }

    @Test
    fun `a length at the cap boundary is not rejected by the cap (fails later on truncation instead)`() = runTest {
        // A length EXACTLY at the cap must pass the cap guard (it is the inclusive max). With no payload
        // bytes actually present it then fails as "frame truncated" — proving the cap did NOT reject it.
        val bodyPlain = ByteArrayOutputStream().apply {
            write(ContainerFormat.TAG_MANIFEST.toInt())
            write(beInt(ContainerFormat.MAX_FRAME_PAYLOAD_BYTES))
        }.toByteArray()

        val container = craftAuthenticatedContainer(bodyPlain)

        val reader = ContainerReaderImpl(crypto)
        val ex = assertFailsWith<MigrationException.StreamInterrupted> {
            reader.peekManifest(container, passphrase)
        }
        // NOT the cap message — the cap allowed it; it failed because the payload bytes aren't there.
        assert(ex.message?.contains("exceeds max") != true) {
            "cap must allow a length == MAX_FRAME_PAYLOAD_BYTES; got: ${ex.message}"
        }
    }

    /**
     * Hand-build a container: plaintext header ‖ AES-CTR(bodyPlain) ‖ HMAC(header ‖ ciphertext).
     * The HMAC is valid, so the reader's verify-before-trust first pass passes and the malicious
     * body reaches the decrypt path (where the cap must catch it).
     */
    private fun craftAuthenticatedContainer(bodyPlain: ByteArray): File {
        val salt = crypto.randomBytes(BackupCrypto.SALT_BYTES)
        val iv = crypto.randomBytes(BackupCrypto.IV_BYTES)
        val header = crypto.buildHeader(salt, iv)
        val keys = crypto.deriveKeys(passphrase, salt)

        val cipher = crypto.newAesCtrCipher(Cipher.ENCRYPT_MODE, keys.encKey, iv)
        val ciphertext = cipher.doFinal(bodyPlain)

        val mac = crypto.newHmacSha256(keys.macKey)
        mac.update(header)
        mac.update(ciphertext)
        val hmac = mac.doFinal()

        return tmp.newFile("crafted-${System.nanoTime()}.ttmig").apply {
            outputStream().use { it.write(header); it.write(ciphertext); it.write(hmac) }
        }
    }

    private fun beInt(v: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
}
