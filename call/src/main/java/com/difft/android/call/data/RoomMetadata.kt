package com.difft.android.call.data

import kotlinx.serialization.Serializable





data class RoomMetadata(
    val callType: String? = null,
    val canPublishAudio: Boolean = true,
    val canPublishVideo: Boolean = true,
    val canPublishScreen: Boolean = true,
)














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
