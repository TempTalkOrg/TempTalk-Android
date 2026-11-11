package com.difft.android.websocket.api.messages.multidevice

import com.google.gson.annotations.SerializedName





data class DeviceInfo(
    @SerializedName("id") val id: Int = 0,
    @SerializedName("name") val name: String? = null,
    @SerializedName("created") val created: Long = 0L,
    @SerializedName("lastSeen") val lastSeen: Long = 0L
)

data class DevicesResponse(
    @SerializedName("devices") val devices: List<DeviceInfo>? = null
)
