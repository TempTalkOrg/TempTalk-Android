package com.difft.android.network.config

import android.util.Base64
import com.difft.android.base.log.lumberjack.L
import com.difft.android.network.BuildConfig
import com.difft.android.network.responses.EncryptedGlobalConfigResponse
import org.json.JSONObject
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object GlobalConfigCrypto {

    private const val SIGN_PREFIX = "GLOBALCONFIG_"
    private const val HKDF_KEY_INFO = "global-config-key"
    private const val HKDF_IV_INFO = "global-config-iv"
    private const val GCM_TAG_BITS = 128

    private val PUBLIC_KEYS: Map<String, String> by lazy {
        parsePublicKeys(BuildConfig.CONFIG_PUBLIC_KEYS)
    }

    fun decryptGlobalConfig(encrypted: EncryptedGlobalConfigResponse): String {
        val keyId = encrypted.keyId
            ?: throw IllegalArgumentException("Missing keyId field")
        val dataB64 = encrypted.data
            ?: throw IllegalArgumentException("Missing data field")
        val nonceB64 = encrypted.nonce
            ?: throw IllegalArgumentException("Missing nonce field")
        val signValue = encrypted.sign
            ?: throw IllegalArgumentException("Missing sign field")

        if (!signValue.startsWith(SIGN_PREFIX)) {
            throw SecurityException("Invalid sign prefix: expected $SIGN_PREFIX")
        }
        val signatureB64 = signValue.removePrefix(SIGN_PREFIX)

        val publicKeyB64 = PUBLIC_KEYS[keyId]
            ?: throw SecurityException("Unknown keyId: \"$keyId\"")

        val signPayload = buildSignPayload(dataB64, keyId, nonceB64)
        val signatureBytes = Base64.decode(signatureB64, Base64.DEFAULT)
        if (!verifySignature(signPayload, signatureBytes, publicKeyB64)) {
            throw SecurityException("ECDSA signature verification failed")
        }
        L.i { "[GlobalConfigCrypto] Signature verified, keyId=$keyId" }

        val psk = hexToBytes(BuildConfig.CONFIG_PSK)
        val nonce = Base64.decode(nonceB64, Base64.DEFAULT)
        val aesKey = hkdfDerive(psk, nonce, HKDF_KEY_INFO, 32)
        val iv = hkdfDerive(psk, nonce, HKDF_IV_INFO, 12)

        val ciphertextWithTag = Base64.decode(dataB64, Base64.DEFAULT)
        val plaintext = aesGcmDecrypt(aesKey, iv, ciphertextWithTag, nonce)

        return String(plaintext, Charsets.UTF_8)
    }

    private fun buildSignPayload(
        dataB64: String,
        keyId: String,
        nonceB64: String
    ): ByteArray {
        return "data=$dataB64&keyId=$keyId&nonce=$nonceB64"
            .toByteArray(Charsets.UTF_8)
    }

    private fun verifySignature(
        payload: ByteArray,
        signatureBytes: ByteArray,
        publicKeyB64: String
    ): Boolean {
        val keyBytes = Base64.decode(publicKeyB64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(keyBytes))

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initVerify(publicKey)
        sig.update(payload)
        return sig.verify(signatureBytes)
    }

    private fun aesGcmDecrypt(
        aesKey: ByteArray,
        iv: ByteArray,
        ciphertextWithTag: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), spec)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * HKDF-SHA256: Extract-then-Expand.
     * JDK has no built-in HKDF, so we implement it manually.
     */
    private fun hkdfDerive(
        ikm: ByteArray,
        salt: ByteArray,
        info: String,
        length: Int
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        var t = ByteArray(0)
        val okm = ByteArray(length)
        var offset = 0
        val infoBytes = info.toByteArray(Charsets.UTF_8)

        for (i in 1..n) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(infoBytes)
            mac.update(i.toByte())
            t = mac.doFinal()
            val copyLen = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, copyLen)
            offset += copyLen
        }
        return okm
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private fun parsePublicKeys(json: String): Map<String, String> {
        if (json.isEmpty()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            L.e { "[GlobalConfigCrypto] Failed to parse public keys: ${e.message}" }
            emptyMap()
        }
    }
}
