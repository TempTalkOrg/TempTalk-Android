package com.difft.android.websocket.api.push

import com.difft.android.websocket.api.util.UuidUtil
import com.google.protobuf.ByteString
import java.util.UUID

/**
 * An ACI is an "Account Identity". They're just UUIDs, but given multiple different things could be UUIDs, this wrapper
 * exists to give us type safety around this *specific type* of UUID.
 */
class ACI private constructor(uuid: UUID) : ServiceId(uuid) {

    override fun toByteString(): ByteString = UuidUtil.toByteString(rawUuid)

    override fun toByteArray(): ByteArray = UuidUtil.toByteArray(rawUuid)

    companion object {
        @JvmStatic
        fun from(uuid: UUID): ACI = ACI(uuid)

        @JvmStatic
        fun from(serviceId: ServiceId): ACI = ACI(serviceId.uuid())

        @JvmStatic
        fun fromNullable(serviceId: ServiceId?): ACI? =
            if (serviceId != null) ACI(serviceId.uuid()) else null

        @JvmStatic
        fun parseOrThrow(raw: String): ACI = from(UUID.fromString(raw))

        @JvmStatic
        fun parseOrNull(raw: String): ACI? {
            val uuid = UuidUtil.parseOrNull(raw)
            return if (uuid != null) from(uuid) else null
        }
    }
}
