package com.difft.android.base.call


data class StartCallRequestBody(
    val type: String,
    val version: Int,
    val timestamp: Long,
    val conversation: String? = null,
    val cipherMessages: List<CipherMessage>? = null,
    val encInfos: List<EncInfo>? = null,
    val encMeta: EncMeta? = null,
    val roomId: String? = null,
    val notification: Notification? = null,
    val publicKey: String? = null,
)

data class CipherMessage(
    val content: String?,
    val registrationId: Int,
    val uid: String?
)

data class EncInfo(
    val emk: String?,
    val uid: String?
)

data class EncMeta(
    val name: String?
)

data class Notification(
    val args: Args,
    val type: Int
)

data class Args(
    val collapseId: String?
)

data class StartCallResponseData(
    val caller: Caller?,
    val createdAt: Long?,
    val emk: String?,
    val encMeta: EncMeta?,
    val extra: List<Extra>?,
    val roomId: String?,
    val version: Int?,
    val missing: List<Missing>?,
    val needsSync: Boolean,
    val publicKey: String?,
    val serviceUrl: String?,
    val stale: List<Stale>?,
    val token: String?,
    val serviceUrls: List<String>?,
    val systemShowTimestamp: Long?
)

data class Caller(
    val did: Int?,
    val uid: String?
)

data class Extra(
    val uid: String?
)

data class Missing(
    val uid: String?
)

data class Stale(
    val identityKey: String?,
    val registrationId: Int,
    val uid: String?
)

data class CallEncryptResult(
    var cipherMessages: List<CipherMessage>? = null,
    var encInfos: List<EncInfo>? = null,
    var publicKey: String? = null
)
val EMPTY_CALL_ENCRYPT_RESULT = CallEncryptResult()

data class ControlMessageRequestBody(
    val roomId: String?,
    val timestamp: Long,
    val cipherMessages: List<CipherMessage>? = null,
    val detailMessageType: Int = 0
)

data class ControlMessageResponseData(
    val needsSync: Boolean,
    val stale: List<Stale>?,
)

data class CallListResponseData(
    val calls: List<CallData>? = null
)

data class CallData(
    var type: String?,
    val version: Int?,
    val createdAt: Long?,
    val roomId: String,
    val caller: CallDataCaller,
    var conversation: String?,
    val encMeta: Any?,
    var callName: String? = null,
    var notifying: Boolean = false,
    var source: CallDataSourceType = CallDataSourceType.UNKNOWN,
    var hasAnotherDeviceJoined: Boolean = false,
    var isInCalling: Boolean = false,
)

data class CallDataCaller(
    val uid: String?,
    val did: Int?
)


data class InviteCallRequestBody(
    val roomId: String,
    val timestamp: Long,
    val cipherMessages: List<CipherMessage>? = null,
    val encInfos: List<EncInfo>? = null,
    val notification: Notification? = null,
    val publicKey: String? = null,
)


data class InviteCallResponseData(
    val invalidUids: List<String>?,
    val stale: List<Stale>?,
    val systemShowTimestamp: Long?
)

data class ServiceUrlData(
    val serviceUrls: List<String>?,
)

data class CallFeedbackRequestBody(
    val userIdentity: String?,
    val userSid: String?,
    val roomSid: String?,
    val roomId: String?,
    val rating: Int,
    val reasons: Map<String, List<Int>> = emptyMap()
)