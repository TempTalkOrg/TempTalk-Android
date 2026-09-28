package com.difft.android.chat.contacts.contactsremark

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


object ContactRemarkUtil {

    /** Wire prefix marking a remark / remark-avatar value as `V1|base64(iv ‖ AES-GCM)`. */
    const val V1_PREFIX = "V1|"

    /** AES-256 key for a contact's private remark data, derived from the contact's uid. */
    fun keyForUid(uid: String): ByteArray =
        (uid + uid + uid).padEnd(32, '+').toByteArray().copyOf(32)

    /** `V1|` ciphertext for the server. Empty plain returns empty (server treats it as clear). */
    fun encryptRemarkV1(uid: String, plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = encryptRemark(plain.toByteArray(), keyForUid(uid))
        return if (cipher.isNullOrEmpty()) "" else V1_PREFIX + cipher
    }

    fun encryptRemark(contentBytes: ByteArray, key: ByteArray): String? {
        try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val params = GCMParameterSpec(128, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, params)
            val encryptData: ByteArray = cipher.doFinal(contentBytes)
            val encrypted = iv + encryptData
            return Base64.encodeBytes(encrypted)
        } catch (e: Exception) {
            L.w { "[ContactRemarkUtil] error: ${e.stackTraceToString()}" }
        }
        return null
    }

    fun decodeRemark(bytes: ByteArray, key: ByteArray): String? {
        try {
            val secretKey = SecretKeySpec(key, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val params = GCMParameterSpec(128, bytes, 0, 12)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, params)
            val decData = cipher.doFinal(bytes, 12, bytes.size - 12)
            return String(decData)
        } catch (e: Exception) {
            L.w { "[ContactRemarkUtil] error: ${e.stackTraceToString()}" }
        }
        return null
    }

    private fun base64EncodeWithLineBreaks(data: ByteArray): String {
        val encoder = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return encoder.chunked(64)
            .joinToString("\n")
    }
}