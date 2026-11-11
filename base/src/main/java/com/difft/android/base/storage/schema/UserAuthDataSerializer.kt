package com.difft.android.base.storage.schema

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.InputStream
import java.io.OutputStream















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
