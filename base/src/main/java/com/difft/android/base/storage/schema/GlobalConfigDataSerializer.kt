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
 * [Serializer] for [GlobalConfigData] using kotlinx-serialization-protobuf.
 *
 * Pattern-identical to [UserAuthDataSerializer]:
 *  - Empty input short-circuits to [defaultValue] (first-run / fresh-install).
 *  - Decode failure is wrapped in [CorruptionException], DataStore's prescribed
 *    signal that triggers [androidx.datastore.core.handlers.ReplaceFileCorruptionHandler]
 *    (configured in `StorageModule.provideSecureConfigDataStore` to fall back
 *    to [GlobalConfigData.EMPTY]).
 *
 * This serializer is wrapped by `EncryptedSerializer` with
 * `SECURE_CONFIG_AAD = "tt.storage.secure_config.v1"` so the plaintext proto
 * bytes never reach disk.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object GlobalConfigDataSerializer : Serializer<GlobalConfigData> {
    private val proto = ProtoBuf { encodeDefaults = true }

    override val defaultValue: GlobalConfigData = GlobalConfigData.EMPTY

    override suspend fun readFrom(input: InputStream): GlobalConfigData = withContext(Dispatchers.IO) {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) return@withContext defaultValue
        try {
            proto.decodeFromByteArray(GlobalConfigData.serializer(), bytes)
        } catch (e: Throwable) {
            throw CorruptionException("Failed to decode GlobalConfigData", e)
        }
    }

    override suspend fun writeTo(t: GlobalConfigData, output: OutputStream) {
        withContext(Dispatchers.IO) {
            output.write(proto.encodeToByteArray(GlobalConfigData.serializer(), t))
        }
    }
}
