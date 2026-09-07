package com.difft.android.chat.attachment

import util.FileSystemUtils

/**
 * The attachment identity a finished upload publishes: which stored object the pointer names, and
 * the digest that describes that object's bytes.
 */
class UploadIdentity(
    val attachmentId: String,
    /** cipherHash bytes — MD5 of the ciphertext the server actually holds. */
    val digest: ByteArray,
    /** True when a value above was taken from the server's response AND differs from the local one. */
    val adoptedFromServer: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as UploadIdentity
        return attachmentId == other.attachmentId &&
            digest.contentEquals(other.digest) &&
            adoptedFromServer == other.adoptedFromServer
    }

    override fun hashCode(): Int =
        (attachmentId.hashCode() * 31 + digest.contentHashCode()) * 31 + adoptedFromServer.hashCode()
}

/**
 * A server-supplied `cipherHash` decoded to digest bytes, or null when the value is unusable.
 *
 * [FileSystemUtils.decodeDigestHex] rejects ONLY an odd character count: every other character
 * decodes through `Character.digit`, which answers -1 for a non-hex character and so yields a 0xFF
 * byte instead of throwing. A base64 or otherwise non-hex value of even length would therefore
 * decode to garbage that looks like a digest — the exact defect this file exists to prevent — so the
 * characters are validated before decoding.
 */
internal fun decodeCipherHashOrNull(cipherHash: String?): ByteArray? {
    val hex = cipherHash?.takeIf { it.isNotBlank() } ?: return null
    if (!hex.all { it.digitToIntOrNull(16) != null }) return null
    return runCatching { FileSystemUtils.decodeDigestHex(hex) }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/**
 * Which attachment id and which digest an upload must publish once `uploadInfo` has answered.
 *
 * The server de-duplicates by fileHash. Two flows that encrypt the SAME plaintext concurrently both
 * miss `isExist` and both upload — the key is content-derived so it is identical, but the IV is
 * random per encryption, so the two ciphertexts differ byte-for-byte. The server keeps one of them
 * and answers the loser's `uploadInfo` with the WINNER's `attachmentId` + `cipherHash`. Publishing
 * the locally computed digest then describes a ciphertext the server discarded: Android never
 * notices (its `verifyMac` HMACs with the content-derived key only), but endpoints that verify the
 * digest — Mac, iOS — fail to load the attachment.
 *
 * Of that pair the digest is the load-bearing half and the id is inert for sending: the wire pointer
 * carries `authorityId` (`DataMessageCreator` sends `id = attachment.authorityId`), neither
 * `PushTextSendJob` nor `ForwardAttachmentReupload` writes an `attachmentId` back to the row, and
 * `DownloadReq` resolves by `authorizeId` + `fileHash` — only `FavoriteAssetUploader` keeps the id as
 * a pointer. A usable `cipherHash` is therefore adopted on its own: withholding it because no
 * `attachmentId` came with it would publish the discarded ciphertext's digest, the exact defect this
 * file exists to prevent. The id is adopted only when the server actually supplied one.
 *
 * `exists` is deliberately not consulted — gson leaves the primitive-typed flag `false` when the
 * field is absent, so presence of the values themselves is the only trustworthy signal.
 *
 * Kept free of WCDB, Android and network types so the choice is unit-testable on its own.
 */
fun resolveUploadIdentity(
    localAttachmentId: String,
    localDigest: ByteArray,
    respAttachmentId: String?,
    respCipherHash: String?
): UploadIdentity {
    val serverId = respAttachmentId?.takeIf { it.isNotBlank() }
    // A malformed digest must not replace a usable one: shipping garbage is the same defect this
    // function exists to avoid. Both sides are the same hash of the same kind of payload, so a
    // decoded value of a different length is not a digest we can publish either.
    val serverDigest = decodeCipherHashOrNull(respCipherHash)
        ?.takeIf { localDigest.isEmpty() || it.size == localDigest.size }
        ?: return UploadIdentity(localAttachmentId, localDigest, adoptedFromServer = false)
    val attachmentId = serverId ?: localAttachmentId
    val differs = attachmentId != localAttachmentId || !serverDigest.contentEquals(localDigest)
    return UploadIdentity(attachmentId, serverDigest, adoptedFromServer = differs)
}
