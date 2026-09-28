package com.difft.android.network.requests

/**
 * signalingKey 合法的 base 64 值，服务端转发消息时会用该值加密消息
 * fetchesMessages 是判断设备是否可用，需要传 true，要不然表示设备不活跃，服务端不会投递消息了，也就收不到消息了
 * registrationId 随机数，用于标记用户的一次登录，每次登录时重新生成(每次登录时也会重置密钥)，发送方发送消息时需要带有该字段，服务端以此判断接收方是否重新登录过（重置了公钥）
 */
data class SignInRequestBody(
    val signalingKey: String,
    val name: String,
    val fetchesMessages: Boolean,
    val registrationId: Int,
    /** Null on a plain sign-in and then left out of the JSON: the server treats absence as the old flow. */
    val transferProof: TransferProof? = null,
)

/**
 * Sent with `PUT /v1/accounts/code/{code}` when the account's identity travelled with its data: the
 * server keeps the account's secondary devices signed in when [identityKey] equals the one it stores
 * and [signature] verifies against that stored key. Both are Base64 in the standard alphabet;
 * [identityKey] is the serialized public key exactly as `PUT /v2/keys` uploads it.
 */
data class TransferProof(
    val identityKey: String,
    val signature: String,
)
