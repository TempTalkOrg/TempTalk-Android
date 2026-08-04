package com.difft.android.websocket.internal.util

import com.difft.android.base.log.lumberjack.L
import com.difft.android.base.utils.Base64
import com.difft.android.websocket.api.push.ACI
import com.difft.android.websocket.api.push.ServiceId
import com.difft.android.websocket.api.push.exceptions.MalformedResponseException
import com.difft.android.websocket.api.util.UuidUtil
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonSerializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializerProvider
import com.google.protobuf.ByteString
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import java.io.IOException
import java.util.UUID

object JsonUtil {

    private val objectMapper = ObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    @JvmStatic
    fun toJson(obj: Any?): String {
        return try {
            objectMapper.writeValueAsString(obj)
        } catch (e: JsonProcessingException) {
            L.w { e.toString() }
            ""
        }
    }

    @JvmStatic
    fun toJsonByteString(obj: Any): ByteString {
        return ByteString.copyFrom(toJson(obj).toByteArray())
    }

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(json: String, clazz: Class<T>): T {
        return objectMapper.readValue(json, clazz)
    }

    @JvmStatic
    @Throws(IOException::class)
    fun <T> fromJson(json: String, typeRef: TypeReference<T>): T {
        return objectMapper.readValue(json, typeRef)
    }

    @JvmStatic
    @Throws(MalformedResponseException::class)
    fun <T> fromJsonResponse(json: String, typeRef: TypeReference<T>): T {
        return try {
            fromJson(json, typeRef)
        } catch (e: IOException) {
            throw MalformedResponseException("Unable to parse entity", e)
        }
    }

    @JvmStatic
    @Throws(MalformedResponseException::class)
    fun <T> fromJsonResponse(body: String, clazz: Class<T>): T {
        return try {
            fromJson(body, clazz)
        } catch (e: IOException) {
            throw MalformedResponseException("Unable to parse entity", e)
        }
    }

    class IdentityKeySerializer : JsonSerializer<IdentityKey>() {
        @Throws(IOException::class)
        override fun serialize(value: IdentityKey, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(Base64.encodeBytesWithoutPadding(value.serialize()))
        }
    }

    class IdentityKeyDeserializer : JsonDeserializer<IdentityKey>() {
        @Throws(IOException::class)
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): IdentityKey {
            return try {
                IdentityKey(Base64.decodeWithoutPadding(p.valueAsString), 0)
            } catch (e: InvalidKeyException) {
                throw IOException(e)
            }
        }
    }

    class UuidSerializer : JsonSerializer<UUID>() {
        @Throws(IOException::class)
        override fun serialize(value: UUID, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    class UuidDeserializer : JsonDeserializer<UUID>() {
        @Throws(IOException::class)
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): UUID? {
            return UuidUtil.parseOrNull(p.valueAsString)
        }
    }

    class AciSerializer : JsonSerializer<ACI>() {
        @Throws(IOException::class)
        override fun serialize(value: ACI, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    class AciDeserializer : JsonDeserializer<ACI>() {
        @Throws(IOException::class)
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ACI? {
            return ACI.parseOrNull(p.valueAsString)
        }
    }

    class ServiceIdSerializer : JsonSerializer<ServiceId>() {
        @Throws(IOException::class)
        override fun serialize(value: ServiceId, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeString(value.toString())
        }
    }

    class ServiceIdDeserializer : JsonDeserializer<ServiceId>() {
        @Throws(IOException::class)
        override fun deserialize(p: JsonParser, ctxt: DeserializationContext): ServiceId? {
            return ServiceId.parseOrNull(p.valueAsString)
        }
    }
}
