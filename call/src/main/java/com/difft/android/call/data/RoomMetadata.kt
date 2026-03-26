package com.difft.android.call.data

import kotlinx.serialization.Serializable

@Serializable
data class RoomMetadata(
    val canPublishAudio: Boolean,
    val canPublishVideo: Boolean,
)