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















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContainerReaderFrameLengthTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val crypto = BackupCryptoImpl()
    private val passphrase = "correct-horse-battery-staple"

    @Test
    fun `oversized 4-byte frame length is rejected as DecryptionFailed (no giant allocation)`() = runTest {
        
        
        val bodyPlain = ByteArrayOutputStream().apply {
            write(ContainerFormat.TAG_MANIFEST.toInt())
            write(beInt(Int.MAX_VALUE)) 
        }.toByteArray()

        val container = craftAuthenticatedContainer(bodyPlain)

        val reader = ContainerReaderImpl(crypto)
        val ex = assertFailsWith<MigrationException.DecryptionFailed> {
            reader.peekManifest(container, passphrase)
        }
        
        assert(ex.message?.contains("exceeds max") == true) {
            "expected the frame-length cap to reject it, got: ${ex.message}"
        }
    }

    @Test
    fun `a length at the cap boundary is not rejected by the cap (fails later on truncation instead)`() = runTest {
        
        
        val bodyPlain = ByteArrayOutputStream().apply {
            write(ContainerFormat.TAG_MANIFEST.toInt())
            write(beInt(ContainerFormat.MAX_FRAME_PAYLOAD_BYTES))
        }.toByteArray()

        val container = craftAuthenticatedContainer(bodyPlain)

        val reader = ContainerReaderImpl(crypto)
        val ex = assertFailsWith<MigrationException.StreamInterrupted> {
            reader.peekManifest(container, passphrase)
        }
        
        assert(ex.message?.contains("exceeds max") != true) {
            "cap must allow a length == MAX_FRAME_PAYLOAD_BYTES; got: ${ex.message}"
        }
    }

    




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
