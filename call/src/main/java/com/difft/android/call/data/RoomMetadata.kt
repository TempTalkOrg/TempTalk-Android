package com.difft.android.call.data

import kotlinx.serialization.Serializable

/**
 * Server-owned room state, held as the current effective value. Room metadata has no client-side
 * write path, so [callType] is the authoritative meeting type — clients no longer infer it from the
 * conversation kind plus participant count.
 *
 * Never decoded directly; incoming payloads are decoded as [RoomMetadataPatch] and merged into the
 * value already in effect. The defaults here are the pre-connect state only.
 *
 * @param callType `1on1` / `group` / `instant`, or null when the server has not sent it — in which
 *   case the local decision keeps running unchanged (see
 *   [com.difft.android.call.session.CallTypeResolver]).
 */
data class RoomMetadata(
    val callType: String? = null,
    val canPublishAudio: Boolean = true,
    val canPublishVideo: Boolean = true,
    val canPublishScreen: Boolean = true,
)

/**
 * Wire form of [RoomMetadata], as delivered on `JoinResponse.room.metadata` and re-broadcast on
 * every `RoomUpdate`.
 *
 * Every field is nullable so that "absent from the payload" stays distinguishable from "sent with a
 * value", which drives two separate requirements:
 *  - a payload missing one key (legacy room, staged rollout) must still yield the keys it does
 *    carry, instead of failing the whole decode and taking [callType] down with it;
 *  - an absent key must leave the current value alone. Defaulting the publish flags to `true`
 *    instead would let an update that only carries `callType` silently re-grant a restriction the
 *    server had imposed earlier, and `CallMediaController` gates the mic and camera on exactly
 *    those flags.
 */
@Serializable
data class RoomMetadataPatch(
    val callType: String? = null,
    val canPublishAudio: Boolean? = null,
    val canPublishVideo: Boolean? = null,
    val canPublishScreen: Boolean? = null,
) {
    fun mergeInto(current: RoomMetadata) = RoomMetadata(
        callType = callType ?: current.callType,
        canPublishAudio = canPublishAudio ?: current.canPublishAudio,
        canPublishVideo = canPublishVideo ?: current.canPublishVideo,
        canPublishScreen = canPublishScreen ?: current.canPublishScreen,
    )
}
