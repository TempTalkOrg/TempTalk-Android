package com.difft.android.websocket.api.util

import com.google.protobuf.ByteString
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID
import java.util.regex.Pattern

object UuidUtil {

    @JvmField
    val UNKNOWN_UUID: UUID = UUID(0, 0)

    private val UUID_PATTERN: Pattern =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", Pattern.CASE_INSENSITIVE)

    @JvmStatic
    fun parse(uuid: String?): Optional<UUID> {
        return Optional.ofNullable(parseOrNull(uuid))
    }

    @JvmStatic
    fun parseOrNull(uuid: String?): UUID? {
        return if (isUuid(uuid)) parseOrThrow(uuid!!) else null
    }

    @JvmStatic
    fun parseOrUnknown(uuid: String?): UUID {
        return if (uuid == null || uuid.isEmpty()) UNKNOWN_UUID else parseOrThrow(uuid)
    }

    @JvmStatic
    fun parseOrThrow(uuid: String): UUID {
        return UUID.fromString(uuid)
    }

    @JvmStatic
    fun parseOrThrow(bytes: ByteArray): UUID {
        val byteBuffer = ByteBuffer.wrap(bytes)
        val high = byteBuffer.long
        val low = byteBuffer.long
        return UUID(high, low)
    }

    @JvmStatic
    fun isUuid(uuid: String?): Boolean {
        return uuid != null && UUID_PATTERN.matcher(uuid).matches()
    }

    @JvmStatic
    fun toByteArray(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.wrap(ByteArray(16))
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        return buffer.array()
    }

    @JvmStatic
    fun toByteString(uuid: UUID): ByteString {
        return ByteString.copyFrom(toByteArray(uuid))
    }

    @JvmStatic
    fun fromByteString(bytes: ByteString): UUID {
        return parseOrThrow(bytes.toByteArray())
    }

    @JvmStatic
    fun fromByteStringOrNull(bytes: ByteString): UUID? {
        return parseOrNull(bytes.toByteArray())
    }

    @JvmStatic
    fun fromByteStringOrUnknown(bytes: ByteString): UUID {
        return fromByteStringOrNull(bytes) ?: UNKNOWN_UUID
    }

    @JvmStatic
    fun parseOrNull(byteArray: ByteArray?): UUID? {
        return if (byteArray != null && byteArray.size == 16) parseOrThrow(byteArray) else null
    }

    @JvmStatic
    fun fromByteStrings(byteStringCollection: Collection<ByteString>): List<UUID> {
        val result = ArrayList<UUID>(byteStringCollection.size)
        for (byteString in byteStringCollection) {
            result.add(fromByteString(byteString))
        }
        return result
    }

    /**
     * Keep only UUIDs that are not the [UNKNOWN_UUID].
     */
    @JvmStatic
    fun filterKnown(uuids: Collection<UUID>): List<UUID> {
        val result = ArrayList<UUID>(uuids.size)
        for (uuid in uuids) {
            if (UNKNOWN_UUID != uuid) {
                result.add(uuid)
            }
        }
        return result
    }
}
