package com.difft.android.base.storage

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.difft.android.base.log.lumberjack.L
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream


















internal class EncryptedSerializer<T>(
    private val delegate: Serializer<T>,
    private val aead: Aead,
    private val aad: ByteArray,
    private val label: String,
) : Serializer<T> {

    override val defaultValue: T = delegate.defaultValue

    override suspend fun readFrom(input: InputStream): T = withContext(Dispatchers.IO) {
        val ciphertext = input.readBytes()
        if (ciphertext.isEmpty()) return@withContext defaultValue
        val plaintext = try {
            aead.decrypt(ciphertext, aad)
        } catch (e: Throwable) {
            L.w { "[Storage][$label] decrypt failed: ${e.javaClass.simpleName}" }
            throw CorruptionException("Failed to decrypt $label DataStore", e)
        }
        delegate.readFrom(plaintext.inputStream())
    }

    override suspend fun writeTo(t: T, output: OutputStream) {
        withContext(Dispatchers.IO) {
            val plaintextBuffer = ByteArrayOutputStream()
            delegate.writeTo(t, plaintextBuffer)
            output.write(aead.encrypt(plaintextBuffer.toByteArray(), aad))
        }
    }
}
