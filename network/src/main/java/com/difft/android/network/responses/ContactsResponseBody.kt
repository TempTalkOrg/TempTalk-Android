package com.difft.android.network.responses

import com.difft.android.websocket.api.messages.PrivateConfigs

data class ContactsDataResponse(
    val contacts: List<ContactResponse>, val directoryVersion: Int = 0
)

data class ContactResponse(
    val number: String? = null,
    val name: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    var publicConfigs: PublicConfigs? = null,
    var privateConfigs: PrivateConfigs? = null,
    val signature: String? = null,
    val timeZone: String? = null,
    val remark: String? = null,
    val joinedAt: String? = null,
    val sourceDescribe: String? = null,
    val findyouDescribe: String? = null
)

data class AvatarResponse(
    val attachmentId: String? = null,
    val encAlgo: String? = null,
    val encKey: String? = null,
)

data class PublicConfigs(
    val meetingVersion: Int? = null,
    val publicName: String? = null,
)