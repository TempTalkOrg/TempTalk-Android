package com.difft.android.network.requests






data class SignInRequestBody(
    val signalingKey: String,
    val name: String,
    val fetchesMessages: Boolean,
    val registrationId: Int,
    
    val transferProof: TransferProof? = null,
)







data class TransferProof(
    val identityKey: String,
    val signature: String,
)
