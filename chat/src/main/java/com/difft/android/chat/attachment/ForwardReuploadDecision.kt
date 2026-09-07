package com.difft.android.chat.attachment

/**
 * What a forward send must do with ONE attachment leaf of the forward tree before the message goes
 * out on the wire.
 *
 * Forwarding does not upload anything: it asks the file-sharing service to re-authorize the file the
 * recipients are about to be pointed at (`isExist`). When that authorization MISSES — the server has
 * expired or reclaimed the file — the pointer this leaf carries is dead, and sending it delivers an
 * attachment nobody can ever download. This decides, per leaf, whether the bytes this device still
 * holds can repair the pointer or whether the send has to fail instead.
 *
 * Kept free of WCDB, Android and network types: which of these four outcomes a leaf gets is the part
 * that determines whether a knowingly-broken forward can leave the device, so it is unit-testable on
 * its own.
 */
sealed interface ForwardLeafUpload {

    /**
     * Nothing to do — the leaf's authorization stands (or the leaf carries no key to upload under,
     * in which case this send never had a pointer of its own to repair). The fast path: untouched.
     */
    data object NotNeeded : ForwardLeafUpload

    /**
     * A legacy plaintext file is on disk: encrypt and upload it exactly like a first-time send. Yields
     * a fresh ciphertext, hence a NEW digest; the key is content-derived so it comes back identical.
     */
    data class FromPlaintext(val basePath: String) : ForwardLeafUpload

    /**
     * Encrypted at rest — the normal case. The stored ciphertext is uploaded as-is: it was produced
     * with (or downloaded for) the recorded key, so its IV and MAC already match, and re-encrypting
     * would need a plaintext that no longer exists on disk.
     */
    data class FromStoredCiphertext(val basePath: String) : ForwardLeafUpload

    /**
     * Authorization missing AND no local bytes to repair it with. The send MUST fail: the recipient
     * would otherwise receive a permanently undownloadable attachment with no signal to the sender.
     */
    data object NoLocalBytes : ForwardLeafUpload
}

/**
 * The action for one forward leaf.
 *
 * `authorityId == 0L` is the miss signal, written onto the leaf by the forward flow when `isExist`
 * answered `exists=false`. It is deliberately the attachment's own field rather than a transient
 * marker: it survives the per-target deep copy, the job's gson serialization and the row it is
 * persisted into, so a send that is interrupted and resumed still knows the pointer is dead. It is
 * also what the value already means everywhere else — "never uploaded" (see
 * `AttachmentSendIdentity.resendSourcePath`).
 *
 * [key] is what the recipient decrypts with and what the server-side file identity is derived from,
 * so a leaf without one cannot be uploaded at all — it was never repairable, and must not become a
 * failed send.
 *
 * Ciphertext is preferred over plaintext when both exist: it is the payload the recipient must
 * receive, and uploading it keeps the recorded key valid without a re-encrypt pass.
 */
fun forwardLeafUpload(
    authorityId: Long,
    key: ByteArray?,
    fileName: String?,
    basePath: String,
    hasPlaintext: Boolean,
    hasCiphertext: Boolean
): ForwardLeafUpload {
    if (authorityId != 0L) return ForwardLeafUpload.NotNeeded
    if (key == null || key.isEmpty()) return ForwardLeafUpload.NotNeeded
    if (fileName.isNullOrEmpty()) return ForwardLeafUpload.NoLocalBytes
    return when {
        hasCiphertext -> ForwardLeafUpload.FromStoredCiphertext(basePath)
        hasPlaintext -> ForwardLeafUpload.FromPlaintext(basePath)
        else -> ForwardLeafUpload.NoLocalBytes
    }
}

/** What one candidate base path holds, so the rule below can be decided without touching a disk. */
data class LeafBytes(val hasPlaintext: Boolean, val hasCiphertext: Boolean)

/**
 * The action for one forward leaf, with the send-time copy source as a SECOND chance.
 *
 * A leaf can reach send time with nothing at its own address while the bytes are still on this
 * device: the materialize-time copy hit a full disk, or the source finished downloading only after
 * that copy had already run. Failing such a send is a false negative — the pointer is repairable
 * from bytes nobody has to download.
 *
 * The source is uploaded from WHERE IT IS; nothing is copied to the leaf's own address. That is what
 * keeps a confidential source out: it carries no source hint at all (`toForwardCopy` refuses to
 * capture one), so it never reaches this second chance and its send still fails rather than the
 * attachment gaining a persistent copy.
 *
 * [sendSourceBasePath] is consulted ONLY on a [ForwardLeafUpload.NoLocalBytes] verdict — it is
 * blocking IO. Answering null is the normal case, not an error: both hints behind it are transient
 * and do not survive the send job's serialization, so a retried send simply keeps the verdict it
 * already had.
 */
fun forwardLeafUpload(
    authorityId: Long,
    key: ByteArray?,
    fileName: String?,
    basePath: String,
    bytesAt: (String) -> LeafBytes,
    sendSourceBasePath: () -> String?
): ForwardLeafUpload {
    val own = bytesAt(basePath)
    val direct = forwardLeafUpload(authorityId, key, fileName, basePath, own.hasPlaintext, own.hasCiphertext)
    if (direct !is ForwardLeafUpload.NoLocalBytes) return direct
    // A leaf with no file name cannot be located at ANY address, so resolving the source would be a
    // blocking read that no answer could rescue.
    if (fileName.isNullOrEmpty()) return direct
    val source = sendSourceBasePath() ?: return direct
    val sourceBytes = bytesAt(source)
    return forwardLeafUpload(authorityId, key, fileName, source, sourceBytes.hasPlaintext, sourceBytes.hasCiphertext)
}
