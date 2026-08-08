package com.difft.android.websocket.api.push

import com.difft.android.websocket.api.util.UuidUtil
import com.google.protobuf.ByteString
import org.signal.libsignal.protocol.SignalProtocolAddress
import java.util.UUID

/**
 * A wrapper around a UUID that represents an identifier for an account. Today, that is either an [ACI].
 * However, that doesn't mean every [ServiceId] is an *instance* of one of those classes. In reality, we often
 * do not know which we have. And it shouldn't really matter.
 *
 * The only times you truly know, and the only times you should actually care, is during CDS refreshes or specific
 * inbound messages that link them together.
 */
open class ServiceId protected constructor(protected val rawUuid: UUID) {

    fun uuid(): UUID = rawUuid

    fun isUnknown(): Boolean = rawUuid == UNKNOWN.rawUuid

    fun isValid(): Boolean = !isUnknown()

    fun toProtocolAddress(deviceId: Int): SignalProtocolAddress =
        SignalProtocolAddress(rawUuid.toString(), deviceId)

    open fun toByteString(): ByteString = UuidUtil.toByteString(rawUuid)

    open fun toByteArray(): ByteArray = UuidUtil.toByteArray(rawUuid)

    override fun toString(): String = rawUuid.toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServiceId) return false
        return rawUuid == other.rawUuid
    }

    override fun hashCode(): Int = rawUuid.hashCode()

    companion object {
        @JvmField
        val UNKNOWN: ServiceId = from(UuidUtil.UNKNOWN_UUID)

        @JvmStatic
        fun from(uuid: UUID): ServiceId = ServiceId(uuid)

        @JvmStatic
        fun parseOrThrow(raw: String): ServiceId = from(UUID.fromString(raw))

        @JvmStatic
        fun parseOrThrow(raw: ByteArray): ServiceId = from(UuidUtil.parseOrThrow(raw))

        @JvmStatic
        fun parseOrNull(raw: String): ServiceId? {
            val uuid = UuidUtil.parseOrNull(raw)
            return if (uuid != null) from(uuid) else null
        }

        @JvmStatic
        fun parseOrNull(raw: ByteArray): ServiceId? {
            val uuid = UuidUtil.parseOrNull(raw)
            return if (uuid != null) from(uuid) else null
        }

        @JvmStatic
        fun parseOrUnknown(raw: String): ServiceId {
            val aci = parseOrNull(raw)
            return aci ?: UNKNOWN
        }

        @JvmStatic
        fun fromByteString(bytes: ByteString): ServiceId = parseOrThrow(bytes.toByteArray())

        @JvmStatic
        fun fromByteStringOrNull(bytes: ByteString): ServiceId? {
            val uuid = UuidUtil.fromByteStringOrNull(bytes)
            return if (uuid != null) from(uuid) else null
        }

        @JvmStatic
        fun fromByteStringOrUnknown(bytes: ByteString): ServiceId {
            val uuid = fromByteStringOrNull(bytes)
            return uuid ?: UNKNOWN
        }

        @JvmStatic
        fun filterKnown(serviceIds: Collection<ServiceId>): List<ServiceId> =
            serviceIds.filter { it != UNKNOWN }
    }
}
