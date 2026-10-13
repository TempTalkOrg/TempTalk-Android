package com.difft.android.migration.v2.model

import com.difft.android.migration.v2.container.AbsentIsEmpty

/**
 * Sub-structures embedded inside a [NeutralMessage]: pure canonical shapes, with no WCDB and no
 * serialization logic, that each platform projects into and expands back out of.
 */

/** Frozen sentinel uid meaning "@all" in a mention; all three platforms use it. */
const val MENTIONS_ALL_UID = "MENTIONS_ALL"

/**
 * A quote/reply reference. The quoted message is identified by the composite `{author, sentAt}`
 * rather than a local primary key, because auto-increment PKs are not portable; FK reconnection on
 * import locates the target by that composite identity.
 *
 * @param attachmentsMeta content-type and name hints for the quoted message's attachments, never
 *   bytes. Quote thumbnails are rebuilt by a post-import backfill pass on each platform.
 * @param referencedMessageNotFound true when the quoting end already knew the quoted message was
 *   missing; null when the platform does not carry it.
 * @param messageMode confidential-mode flag of the quoted message; null when not carried.
 */
data class NeutralQuote(
    @field:AbsentIsEmpty val author: String,
    val sentAt: Long,
    val textPreview: String?,
    val attachmentsMeta: List<NeutralAttachmentMeta> = emptyList(),
    val referencedMessageNotFound: Boolean? = null,
    val messageMode: Int? = null,
)

/**
 * Lightweight attachment metadata for [NeutralQuote] and [NeutralForward] previews — not the
 * byte-carrying record, which is [NeutralAttachment].
 *
 * @param stableId the portable attachment id, tying this preview entry to its byte-carrying record
 *   so the importer re-links it to the right forward or quote.
 */
data class NeutralAttachmentMeta(
    val stableId: String,
    val contentType: String?,
    val fileName: String?,
)

/**
 * A single reaction, carried as a sub-item of the target message's [NeutralMessage.reactions] — the
 * canonical carrier, which iOS and Android fold their standalone rows into on export.
 *
 * @param removed true if this reaction was retracted rather than active.
 */
data class NeutralReaction(
    val emoji: String,
    val fromUid: String,
    val removed: Boolean,
    val sentAt: Long,
)

/**
 * An @-mention span inside the message body.
 *
 * @param uid mentioned user's uid; `@all` uses the [MENTIONS_ALL_UID] sentinel.
 * @param type wire-proto mention type verbatim (INTERNAL=0 / EXTERNAL=1); null reads as INTERNAL.
 *   Room-level @ tags are derived from [uid], never from this.
 */
data class NeutralMention(
    val uid: String,
    val start: Int,
    val length: Int,
    val type: Int? = null,
)

/**
 * A contact card attached to a message, flattening Android's `sharedContact` name parts and its N
 * `sharedContactPhone` rows into one neutral shape.
 *
 * Only what Android persists is modeled: avatar, email, address and organization are not stored, so
 * carrying them would be dead weight. Applies to normal and confidential cards alike — a confidential
 * card's content is in the same tables and only its render gate differs.
 */
data class NeutralSharedContact(
    val givenName: String? = null,
    val familyName: String? = null,
    val namePrefix: String? = null,
    val nameSuffix: String? = null,
    val middleName: String? = null,
    val displayName: String? = null,
    val phones: List<NeutralSharedContactPhone> = emptyList(),
)

/** One phone number of a [NeutralSharedContact], mirroring `SharedContactPhoneModel`. */
data class NeutralSharedContactPhone(
    val value: String? = null,
    val type: Int = 0,
    val label: String? = null,
)

/**
 * One node of a combined-forward tree; a forwarded entry can itself contain forwards, hence [nested].
 *
 * @param sentAt the forwarded message's own sentAt — the wire `Forward.id`, not the server timestamp,
 *   which is often 0 on a sub-node. Every platform keys a forward node by `{author, sentAt}`.
 * @param nested nested forwards. A level can hold several children, so this is a list: a single-child
 *   model cannot round-trip a multi-entry combined forward.
 */
data class NeutralForward(
    @field:AbsentIsEmpty val author: String,
    val sentAt: Long,
    val body: String?,
    val attachmentsMeta: List<NeutralAttachmentMeta> = emptyList(),
    val nested: List<NeutralForward> = emptyList(),
)

/**
 * The carrier for a merged-forward card: N parallel top-level forwards, each itself a recursive
 * [NeutralForward] tree. The card UI switches on the root count, so the roots must round-trip as
 * parallel top-level forwards and must not be collapsed into one root's children.
 *
 * @param isFromGroup mirrors `ForwardContextModel.isFromGroup`, the only meaningful persisted field
 *   on that row besides its non-portable local PK, and the read-back path reads it.
 * @param roots the parallel top-level forwards, in source order; the size is the number of messages
 *   in the merged forward.
 */
data class NeutralForwardContext(
    val isFromGroup: Boolean = false,
    val roots: List<NeutralForward>,
)

