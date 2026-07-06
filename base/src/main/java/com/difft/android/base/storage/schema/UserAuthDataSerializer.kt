package com.difft.android.base.storage.schema

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream

/**
 * kotlinx-serialization Proto codec for [UserAuthData].
 *
 * - `encodeDefaults = true` preserves explicit defaults (empty strings, `0L`, `false`)
 *   so a freshly-cleared blob and a never-written blob serialize identically.
 * - Empty-input short-circuit returns [defaultValue] so DataStore's zero-byte
 *   "fresh file" state doesn't trigger a corruption signal.
 * - Any decode failure (truncation, wrong proto, malformed bytes) is mapped to
 *   [CorruptionException] — DataStore's prescribed signal that routes through
 *   `ReplaceFileCorruptionHandler` (or `StorageBoundUserManagerImpl.readAuthDataOrRecover`
 *   in Task 7) for R3 recovery.
 *
 * Wrapped by [com.difft.android.base.storage.EncryptedSerializer] in
 * `StorageModule.provideSecureUserDataStore` to add Tink AEAD.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object UserAuthDataSerializer : Serializer<UserAuthData> {
    private val proto = ProtoBuf { encodeDefaults = true }

    override val defaultValue: UserAuthData = UserAuthData.EMPTY

    override suspend fun readFrom(input: InputStream): UserAuthData = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return@withContext defaultValue
        try {
            proto.decodeFromByteArray(UserAuthData.serializer(), bytes)
        } catch (e: Throwable) {
            throw CorruptionException("Failed to decode UserAuthData", e)
        }
    }

    override suspend fun writeTo(t: UserAuthData, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(proto.encodeToByteArray(UserAuthData.serializer(), t))
        }
    }
}
