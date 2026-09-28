package com.difft.android.chat.attachment

/**
 * How a write locates the ONE attachment row it may touch.
 *
 * The server-side attachment id is shared by every forwarded copy of the same file, so an id-based
 * locator can update another message's row (issue #1178). [ByLocalId] is therefore the only correct
 * locator; the other two exist solely for a job persisted before the localId column, which has no
 * local id to locate by until it recovers one from the database.
 *
 * Kept free of WCDB types so the decision — the part that actually determines which rows a write can
 * reach — is unit-testable without the native library.
 */
sealed interface AttachmentRowTarget {
    /** Exactly one row. Sibling copies sharing the server-side id are out of reach by construction. */
    data class ByLocalId(val localId: String) : AttachmentRowTarget

    /** Legacy locator: a message's own attachment. Matches every forward copy owned by that message. */
    data class ByIdAndMessage(val attachmentId: String, val messageId: String) : AttachmentRowTarget

    /** Weakest legacy locator: every row carrying this server-side id. */
    data class ById(val attachmentId: String) : AttachmentRowTarget
}

/** The strongest locator available for these identifiers. */
fun attachmentRowTarget(localId: String?, attachmentId: String, messageId: String?): AttachmentRowTarget = when {
    !localId.isNullOrEmpty() -> AttachmentRowTarget.ByLocalId(localId)
    !messageId.isNullOrEmpty() -> AttachmentRowTarget.ByIdAndMessage(attachmentId, messageId)
    else -> AttachmentRowTarget.ById(attachmentId)
}

/**
 * The per-copy local id a job persisted before the localId column may adopt, out of the [candidates]
 * its legacy locator named.
 *
 * Only an UNAMBIGUOUS match is adopted: writing an id into one of several rows sharing a server-side
 * attachment id would hand a sibling copy an identity it was never rendered under. A row that has no
 * id yet answers with the deterministic id every reader already synthesizes for it — the same value
 * the migration's backfill persists — so adopting it can never disagree with the key the bubble
 * collects progress under. Null means "keep the pre-localId key".
 */
fun <T> adoptableLocalId(
    candidates: List<T>,
    localIdOf: (T) -> String?,
    synthesizedLocalIdOf: (T) -> String
): String? {
    val row = candidates.singleOrNull() ?: return null
    return localIdOf(row)?.takeIf { it.isNotEmpty() } ?: synthesizedLocalIdOf(row)
}

/**
 * The per-copy key of a RESOLVED attachment row — its directory name AND its progress-map key, which
 * must be one value: a file written under one key while progress is emitted under another leaves the
 * bubble's spinner permanently stuck.
 *
 * [rowLocalId] is the row's own persisted identity — the same first term as
 * `AttachmentPathResolver.directoryKeyForRow`. [jobLocalId] covers a row this job named but could not
 * safely adopt an id into. A row with neither answers with its deterministic synthesized id — the
 * same value the reading mapper gives the domain attachment, so `getAttachmentIdForProgress` collects
 * on exactly this key, and the same value the migration's backfill persists. Without that last term a
 * job persisted before the localId key emits under its message id while the bubble collects under the
 * synthesized one, and the two never meet.
 */
fun attachmentRowKey(rowLocalId: String?, jobLocalId: String?, synthesizedLocalId: String): String =
    rowLocalId?.takeIf { it.isNotEmpty() }
        ?: jobLocalId?.takeIf { it.isNotEmpty() }
        ?: synthesizedLocalId

/**
 * Picks the row a LEGACY job meant, out of the rows sharing its server-side attachment id.
 *
 * [legacyKey] is the single identifier such a job carried — the owner message id for a normal
 * attachment, the authority id for a single-forward bubble — so it is tried as both. When it names
 * none of the candidates the first row is taken: every candidate holds the same remote file, so the
 * worst case is that a sibling copy gets materialized and the intended bubble re-downloads, never a
 * wrong file at a right address.
 */
fun <T> pickLegacyAttachmentRow(
    candidates: List<T>,
    legacyKey: String,
    messageIdOf: (T) -> String?,
    authorityIdOf: (T) -> Long?
): T? {
    if (candidates.size <= 1) return candidates.firstOrNull()
    return candidates.firstOrNull { messageIdOf(it) == legacyKey }
        ?: candidates.firstOrNull { authorityIdOf(it)?.toString() == legacyKey }
        ?: candidates.first()
}
