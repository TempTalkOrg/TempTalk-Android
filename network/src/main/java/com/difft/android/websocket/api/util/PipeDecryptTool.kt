package com.difft.android.websocket.api.util

import com.difft.android.websocket.util.Base64
import java.io.IOException
import java.security.InvalidAlgorithmParameterException
import java.security.InvalidKeyException
import java.security.NoSuchAlgorithmException
import java.util.Arrays
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Mac
import javax.crypto.NoSuchPaddingException
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object PipeDecryptTool {
    private const val TAG = "PipeDecryptTool"
    private const val SUPPORTED_VERSION = 1
    private const val CIPHER_KEY_SIZE = 32
    private const val MAC_KEY_SIZE = 20
    private const val MAC_SIZE = 10

    private const val VERSION_OFFSET = 0
    private const val VERSION_LENGTH = 1
    private const val IV_OFFSET = VERSION_OFFSET + VERSION_LENGTH
    private const val IV_LENGTH = 16
    private const val CIPHERTEXT_OFFSET = IV_OFFSET + IV_LENGTH

    @Throws(IOException::class)
    fun getCipherKey(signalingKey: String): SecretKeySpec {
        val signalingKeyBytes = Base64.decode(signalingKey)
        val cipherKey = ByteArray(CIPHER_KEY_SIZE)
        System.arraycopy(signalingKeyBytes, 0, cipherKey, 0, cipherKey.size)
        return SecretKeySpec(cipherKey, "AES")
    }


    @Throws(IOException::class)
    fun getMacKey(signalingKey: String): SecretKeySpec {
        val signalingKeyBytes = Base64.decode(signalingKey)
        val macKey = ByteArray(MAC_KEY_SIZE)
        System.arraycopy(
            signalingKeyBytes,
            CIPHER_KEY_SIZE,
            macKey,
            0,
            macKey.size
        )
        return SecretKeySpec(macKey, "HmacSHA256")
    }

    @Throws(IOException::class)
    fun verifyMac(ciphertext: ByteArray, macKey: SecretKeySpec) {
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(macKey)
            if (ciphertext.size < MAC_SIZE + 1) throw IOException("Invalid MAC!")
            mac.update(ciphertext, 0, ciphertext.size - MAC_SIZE)
            val ourMacFull = mac.doFinal()
            val ourMacBytes = ByteArray(MAC_SIZE)
            System.arraycopy(ourMacFull, 0, ourMacBytes, 0, ourMacBytes.size)
            val theirMacBytes = ByteArray(MAC_SIZE)
            System.arraycopy(
                ciphertext,
                ciphertext.size - MAC_SIZE,
                theirMacBytes,
                0,
                theirMacBytes.size
            )
            if (!Arrays.equals(ourMacBytes, theirMacBytes)) {
                throw IOException("Invalid MAC compare!")
            }
        } catch (e: NoSuchAlgorithmException) {
            throw java.lang.AssertionError(e)
        } catch (e: InvalidKeyException) {
            throw java.lang.AssertionError(e)
        }
    }

    @Throws(IOException::class)
    fun getPlaintext(ciphertext: ByteArray, cipherKey: SecretKeySpec): ByteArray? {
        return try {
            val ivBytes = ByteArray(IV_LENGTH)
            System.arraycopy(ciphertext, IV_OFFSET, ivBytes, 0, ivBytes.size)
            val iv = IvParameterSpec(ivBytes)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, cipherKey, iv)
            cipher.doFinal(
                ciphertext, CIPHERTEXT_OFFSET,
                ciphertext.size - VERSION_LENGTH - IV_LENGTH - MAC_SIZE
            )
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        } catch (e: NoSuchPaddingException) {
            throw AssertionError(e)
        } catch (e: InvalidKeyException) {
            throw AssertionError(e)
        } catch (e: InvalidAlgorithmParameterException) {
            throw AssertionError(e)
        } catch (e: IllegalBlockSizeException) {
            throw AssertionError(e)
        } catch (e: BadPaddingException) {
            throw IOException("Bad padding?")
        }
    }
}