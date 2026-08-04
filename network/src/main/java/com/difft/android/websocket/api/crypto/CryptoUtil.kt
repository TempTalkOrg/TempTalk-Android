package com.difft.android.websocket.api.crypto

import java.security.InvalidKeyException
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {

    private const val HMAC_SHA256 = "HmacSHA256"

    @JvmStatic
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        return try {
            val mac = Mac.getInstance(HMAC_SHA256)
            mac.init(SecretKeySpec(key, HMAC_SHA256))
            mac.doFinal(data)
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        } catch (e: InvalidKeyException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun sha256(data: ByteArray): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(data)
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun sha512(data: ByteArray): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.digest(data)
        } catch (e: NoSuchAlgorithmException) {
            throw AssertionError(e)
        }
    }

    @JvmStatic
    fun bytesToHex(hash: ByteArray): String {
        val hexString = StringBuilder(2 * hash.size)
        for (i in hash.indices) {
            val hex = Integer.toHexString(0xff and hash[i].toInt())
            if (hex.length == 1) {
                hexString.append('0')
            }
            hexString.append(hex)
        }
        return hexString.toString()
    }
}
