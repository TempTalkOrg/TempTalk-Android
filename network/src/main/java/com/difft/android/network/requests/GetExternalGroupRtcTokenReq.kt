package com.difft.android.network.requests

data class GetExternalGroupRtcTokenReq(
    val channelName: String,
    val meetingName: String,
    val invite: List<String>
)
