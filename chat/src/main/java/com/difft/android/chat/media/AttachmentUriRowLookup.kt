package com.difft.android.chat.media

/**
 * The identities the key segment of an attachment content uri has ever meant, in the order they must
 * be tried — newest first.
 */
enum class AttachmentUriIdentity {
    /** Today's segment: the per-copy local identity. */
    LOCAL_ID,

    /** A normal message's own attachment — also the historic segment for one. */
    MESSAGE_ID,

    /** The historic segment for a forwarded attachment; numeric. */
    AUTHORITY_ID,

    /**
     * Today's segment for a row the backfill has not reached: its `localId` column is still NULL, so
     * readers derive a deterministic id from the row instead and mint uris under THAT. The rows are
     * supplied narrowed by file name; the caller matches them by their synthesized id.
     */
    SYNTHESIZED_LOCAL_ID
}

/** Whether [key] has the shape of a synthesized local id (`UUID.toString()`). */
private fun isUuidShaped(key: String): Boolean =
    key.length == 36 && key[8] == '-' && key[13] == '-' && key[18] == '-' && key[23] == '-' &&
        key.all { it == '-' || (it in '0'..'9') || (it in 'a'..'f') || (it in 'A'..'F') }

/**
 * Resolves the attachment row a content uri refers to.
 *
 * The content provider is the one boundary with no calling context: an external app, Glide, or a
 * MediaPlayer can hand back a uri minted by any past version of the app, so the key can only be
 * TRIED against each identity in turn. Rows are supplied by [lookup] rather than queried here, which
 * keeps the ORDER — the part that decides which file a legacy uri resolves to — independent of the
 * database.
 *
 * Within one identity, a row whose file name matches wins; otherwise the first row is taken, matching
 * the pre-existing behaviour for rows that carry no usable file name.
 *
 * LOCAL_ID and MESSAGE_ID are both opaque strings, so an ambiguous match needs a random UUID to
 * collide with a message id — a documented non-concern.
 *
 * [synthesizedLocalIdOf] is what closes the pre-backfill window: while a row's `localId` column is
 * still NULL it matches neither LOCAL_ID nor (for a forward row, which carries no messageId)
 * MESSAGE_ID, yet the uri handed out for it was minted under the id derived from that row. The
 * derivation is deterministic, so the reverse match is exact.
 */
fun <T> resolveAttachmentRowByUriKey(
    key: String,
    fileName: String,
    fileNameOf: (T) -> String?,
    synthesizedLocalIdOf: (T) -> String? = { null },
    lookup: (AttachmentUriIdentity, String) -> List<T>
): T? {
    fun pick(rows: List<T>): T? = rows.firstOrNull { fileNameOf(it) == fileName } ?: rows.firstOrNull()

    if (key.isEmpty()) return null
    pick(lookup(AttachmentUriIdentity.LOCAL_ID, key))?.let { return it }
    pick(lookup(AttachmentUriIdentity.MESSAGE_ID, key))?.let { return it }
    // Only a numeric segment can ever have been an authorityId.
    if (key.toLongOrNull() != null) return pick(lookup(AttachmentUriIdentity.AUTHORITY_ID, key))
    // A non-numeric segment that is not a persisted id can still be a synthesized one. Only a
    // UUID-shaped key can be, so anything else stops here rather than paying for a scan.
    if (!isUuidShaped(key)) return null
    return lookup(AttachmentUriIdentity.SYNTHESIZED_LOCAL_ID, key)
        .firstOrNull { synthesizedLocalIdOf(it) == key }
}
