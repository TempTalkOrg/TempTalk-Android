package com.difft.android.websocket.api.messages

class PublicKeyInfo(
    val uid: String,
    val identityKey: String,
    val registrationId: Int,
    val resetIdentityKeyTime: Long
)

data class GetPublicKeysResp(
    val keys: List<PublicKeyInfo>
)

data class GetPublicKeysReq(
    val uids: List<String>? = null,
    val beginTimestamp: Long? = null,
)