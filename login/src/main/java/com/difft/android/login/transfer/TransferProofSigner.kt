package com.difft.android.login.transfer

import com.difft.android.base.utils.Base64
import com.difft.android.network.requests.TransferProof
import org.signal.libsignal.protocol.IdentityKeyPair









object TransferProofSigner {
    private const val DOMAIN_PREFIX = "tt-acct-transfer-v1|ik-possession"
    private const val SEPARATOR = "|"

    
    fun message(uid: String, identityKeyBase64: String, loginToken: String): ByteArray =
        listOf(DOMAIN_PREFIX, uid, identityKeyBase64, loginToken).joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)

    fun build(uid: String, identity: IdentityKeyPair, loginToken: String): TransferProof {
        val identityKeyBase64 = Base64.encodeBytesWithoutPadding(identity.publicKey.serialize())
        val signature = identity.privateKey.calculateSignature(message(uid, identityKeyBase64, loginToken))
        return TransferProof(identityKey = identityKeyBase64, signature = Base64.encodeBytesWithoutPadding(signature))
    }
}
