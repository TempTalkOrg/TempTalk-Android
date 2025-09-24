package com.difft.android.chat.contacts.contactsremark

import org.thoughtcrime.securesms.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec


object ContactRemarkUtil {

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
            e.printStackTrace()
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
            e.printStackTrace()
        }
        return null
    }

    private fun base64EncodeWithLineBreaks(data: ByteArray): String {
        val encoder = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        return encoder.chunked(64)
            .joinToString("\n")
    }
}