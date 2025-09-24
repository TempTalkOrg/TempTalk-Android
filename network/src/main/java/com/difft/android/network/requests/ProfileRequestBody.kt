package com.difft.android.network.requests


data class ProfileRequestBody(
    val avatar: String? = null,
    val name: String? = null,
    val meetingVersion: Int? = null,
    val msgEncVersion: Int? = null,// 1: Encryption Disabled, 2: Encryption Enabled
    val privateConfigs: PrivateConfigsRequestBody? = null,
)

data class PrivateConfigsRequestBody(
    val saveToPhotos: Boolean? = null,
    val globalNotification: Int? = null
)

data class AvatarRequestBody(
    val encAlgo: String?,
    val encKey: String?,
    val attachmentId: String?
)