package com.difft.android.websocket.internal.crypto

import com.google.protobuf.ByteString
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kdf.HKDF
import org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionEnvelope
import org.whispersystems.signalservice.internal.push.ProvisioningProtos.ProvisionMessage
import com.difft.android.websocket.internal.util.Util
import java.security.NoSuchAlgorithmException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Mac
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.SecretKeySpec

class PrimaryProvisioningCipher(private val theirPublicKey: ECPublicKey) {

    @Throws(InvalidKeyException::class)
    fun encrypt(message: ProvisionMessage): ByteArray {
        val ourKeyPair = ECKeyPair.generate()
        val sharedSecret = ourKeyPair.privateKey.calculateAgreement(theirPublicKey)
        val derivedSecret = HKDF.deriveSecrets(sharedSecret, PROVISIONING_MESSAGE.toByteArray(), 64)
        val parts = Util.split(derivedSecret, 32, 32)

        val version = byteArrayOf(0x01)
        val ciphertext = getCiphertext(parts[0], message.toByteArray())
        val mac = getMac(parts[1], Util.join(version, ciphertext))
        val body = Util.join(version, ciphertext, mac)

        return ProvisionEnvelope.newBuilder()
            .setPublicKey(ByteString.copyFrom(ourKeyPair.publicKey.serialize()))
            .setBody(ByteString.copyFrom(body))
            .build()
            .toByteArray()
    }

    private fun getCiphertext(key: ByteArray, message: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))

            Util.join(cipher.iv, cipher.doFinal(message))
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        } catch (e: NoSuchPaddingException) {
            throw AssertionError(e)
        } catch (e: java.security.InvalidKeyException) {
            throw AssertionError(e)
        } catch (e: IllegalBlockSizeException) {
            throw AssertionError(e)
        } catch (e: BadPaddingException) {
            throw AssertionError(e)
        }
    }

    private fun getMac(key: ByteArray, message: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))

            mac.doFinal(message)
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        } catch (e: java.security.InvalidKeyException) {
            throw AssertionError(e)
        }
    }

    companion object {
        const val PROVISIONING_MESSAGE = "TextSecure Provisioning Message"
    }
}
