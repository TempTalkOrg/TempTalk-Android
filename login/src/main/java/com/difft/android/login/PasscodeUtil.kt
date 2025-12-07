package com.difft.android.login

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


object PasscodeUtil {

    var needRecordLastUseTime = true

    const val DEFAULT_TIMEOUT = 300
    val TIMEOUT_LIST = listOf(60, 300, 900, 1800, 3600, 0)

    private const val ITERATIONS = 10000
    private const val KEY_LENGTH = 256

    private fun generateSalt(length: Int = 16): ByteArray {
        val salt = ByteArray(length)
        SecureRandom().nextBytes(salt)
        return salt
    }

    fun pbkdf2Hash(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    fun ByteArray.toHex(): String {
        return joinToString("") { "%02x".format(it) }
    }

    fun createSaltAndHashByPassword(password: String): Pair<String, String> {
        val salt = generateSalt()
        val hashedPassword = pbkdf2Hash(password, salt)
        return Pair(salt.toHex(), hashedPassword.toHex())
    }

    fun verifyPassword(storedHash: String, storedSalt: String, passwordAttempt: String): Boolean {
        val salt = storedSalt.hexStringToByteArray()
        val hashAttempt = pbkdf2Hash(passwordAttempt, salt)
        return hashAttempt.toHex() == storedHash
    }

    fun String.hexStringToByteArray(): ByteArray {
        val result = ByteArray(this.length / 2)
        for (i in this.indices step 2) {
            result[i / 2] = ((this[i].toString().toInt(16) shl 4) + this[i + 1].toString().toInt(16)).toByte()
        }
        return result
    }
}
