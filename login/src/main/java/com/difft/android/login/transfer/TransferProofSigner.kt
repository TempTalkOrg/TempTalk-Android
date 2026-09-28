package com.difft.android.login.transfer

import com.difft.android.base.utils.Base64
import com.difft.android.network.requests.TransferProof
import org.signal.libsignal.protocol.IdentityKeyPair

/**
 * Builds the `transferProof` a restore continuation sends with its sign-in: proof that this device
 * holds the private half of the identity that travelled with the account's data, bound to the
 * server-issued transfer token. Signed message = `tt-acct-transfer-v1|ik-possession` (a domain
 * prefix that itself contains a bar), the uid as the server names the account, the Base64 identity
 * key as sent, and the login token as received, joined by `|` as UTF-8. The signature is the
 * identity's XEdDSA, as in `PUT /v2/keys`; the server verifies against the key it stores.
 */
object TransferProofSigner {
    private const val DOMAIN_PREFIX = "tt-acct-transfer-v1|ik-possession"
    private const val SEPARATOR = "|"

    /** The exact bytes that are signed; kept apart from [build] so a vector can be checked byte for byte. */
    fun message(uid: String, identityKeyBase64: String, loginToken: String): ByteArray =
        listOf(DOMAIN_PREFIX, uid, identityKeyBase64, loginToken).joinToString(SEPARATOR).toByteArray(Charsets.UTF_8)

    fun build(uid: String, identity: IdentityKeyPair, loginToken: String): TransferProof {
        val identityKeyBase64 = Base64.encodeBytesWithoutPadding(identity.publicKey.serialize())
        val signature = identity.privateKey.calculateSignature(message(uid, identityKeyBase64, loginToken))
        return TransferProof(identityKey = identityKeyBase64, signature = Base64.encodeBytesWithoutPadding(signature))
    }
}
