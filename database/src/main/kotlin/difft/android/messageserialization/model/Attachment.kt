package difft.android.messageserialization.model

import java.io.Serializable
import java.util.UUID

/**
 * Content type for long text messages converted to file attachments.
 * This type indicates that the attachment contains plain text content
 * that should be displayed as text rather than as a file attachment.
 */
const val CONTENT_TYPE_LONG_TEXT = "text/x-signal-plain"

data class Attachment(
    /**
     * Server-side attachment id, shared by every local copy of the same file — it identifies a remote
     * object, never a local one, so nothing local may be located by it. Empty on everything created
     * since [localId] took over that job; it survives only to resolve rows written before then.
     */
    val id: String,
    var authorityId: Long, //authorityId
    var contentType: String,
    var key: ByteArray?,
    var size: Int,
    var thumbnail: ByteArray?,
    var digest: ByteArray?,
    var fileName: String?,
    var flags: Int, //0: normal, 1: audio
    var width: Int,
    var height: Int,
    var path: String?,
    var status: Int,
    var playProgress: Int = 0,
    var isPlaying: Boolean = false,
    var fileHash: String? = null,
    var totalTime: Long? = 0,
    var amplitudes: List<Float>? = null,
    /**
     * Local identity of this attachment copy — generated at construction, never taken from a server
     * response. Persisted to `AttachmentModel.localId`. It is what every local operation addresses
     * by: the file's directory (`AttachmentPathResolver`), the row a write may touch, the progress
     * key a bubble collects on.
     *
     * `copy()` INHERITS it (data-class semantics): a call site that produces a genuinely NEW local
     * copy of an attachment must pass a fresh id explicitly.
     *
     * Deliberately excluded from [equals] / [hashCode]: a row whose column is still NULL gets a
     * freshly synthesized id on every read, so including it would make two reads of the same row
     * unequal and churn list diffing.
     */
    val localId: String = UUID.randomUUID().toString(),
    /**
     * Ownership marker: true when this attachment belongs to a Forward tree instead of to a message
     * of its own.
     *
     * Addressing no longer reads it — every attachment lives under its own [localId]. What still
     * needs it is the MIGRATION: a forwarded copy's pre-per-copy address was keyed by `authorityId`,
     * an address a normal attachment must never be offered.
     *
     * Set at the boundaries that produce forward-tree attachments — the DB read (`forwardModelDatabaseId
     * != null`), wire ingest, and the forward-send copy — never inferred from [id] or [authorityId],
     * neither of which distinguishes a forward copy, and never from nesting depth.
     *
     * `copy()` inherits it.
     */
    val isForwardCopy: Boolean = false
) : Serializable {
    /**
     * On-disk source this forward copy should be materialized from at send time, captured while the
     * ORIGINAL message context was still available (see `forwardSourceFilePath`). Null means "nothing
     * readable locally, or the source is confidential" — the copy then stays LOADING and downloads.
     *
     * Transient by design: it is a send-time hint, not row content. Never persisted, never serialized
     * into a job, and never part of [equals] / [hashCode].
     */
    @Transient
    var forwardSourceFilePath: String? = null

    /**
     * Fallback source for this forward copy, set only when [forwardSourceFilePath] found nothing:
     * the ORIGINAL attachment these bytes come from, plus the id of the message that owned it.
     *
     * Capture runs on the caller's (main) thread and may only LOOK at the current address, but the
     * file may still be sitting at a pre-per-copy one. Carrying the original's identity lets the
     * send-time materializer — which runs on IO — ask the migration seam for it before the copy
     * degrades to a download. Nothing here knows what a legacy address looks like; that stays with
     * the migration.
     *
     * Transient for the same reason as [forwardSourceFilePath]: a send-time hint, never row content.
     */
    @Transient
    var forwardSourceFallback: ForwardSourceFallback? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Attachment

        if (authorityId != other.authorityId) return false
        if (size != other.size) return false
        if (flags != other.flags) return false
        if (width != other.width) return false
        if (height != other.height) return false
        if (status != other.status) return false
        if (playProgress != other.playProgress) return false
        if (isPlaying != other.isPlaying) return false
        if (totalTime != other.totalTime) return false
        if (id != other.id) return false
        if (contentType != other.contentType) return false
        if (key != null) {
            if (other.key == null) return false
            if (!key.contentEquals(other.key)) return false
        } else if (other.key != null) return false
        if (thumbnail != null) {
            if (other.thumbnail == null) return false
            if (!thumbnail.contentEquals(other.thumbnail)) return false
        } else if (other.thumbnail != null) return false
        if (digest != null) {
            if (other.digest == null) return false
            if (!digest.contentEquals(other.digest)) return false
        } else if (other.digest != null) return false
        if (fileName != other.fileName) return false
        if (path != other.path) return false
        if (fileHash != other.fileHash) return false
        if (amplitudes != other.amplitudes) return false

        return true
    }

    override fun hashCode(): Int {
        var result = authorityId.hashCode()
        result = 31 * result + size
        result = 31 * result + flags
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + status
        result = 31 * result + playProgress
        result = 31 * result + isPlaying.hashCode()
        result = 31 * result + (totalTime?.hashCode() ?: 0)
        result = 31 * result + id.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + (key?.contentHashCode() ?: 0)
        result = 31 * result + (thumbnail?.contentHashCode() ?: 0)
        result = 31 * result + (digest?.contentHashCode() ?: 0)
        result = 31 * result + (fileName?.hashCode() ?: 0)
        result = 31 * result + (path?.hashCode() ?: 0)
        result = 31 * result + (fileHash?.hashCode() ?: 0)
        result = 31 * result + (amplitudes?.hashCode() ?: 0)
        return result
    }
}

/**
 * Where a forward copy's bytes can still be looked for when nothing was readable at the original
 * attachment's current address: the [original] itself — the only object that can be resolved to an
 * address, since the copy carries a different identity — and [legacyOwnerMessageId], the message
 * that owned it. Both are plain data; interpreting the owner id as an address is the migration's
 * business alone.
 */
class ForwardSourceFallback(
    val original: Attachment,
    val legacyOwnerMessageId: String?
)

/** Bitmask flag on [Attachment.flags], aligned with proto AttachmentPointer.Flags.GIF. */
const val FLAG_GIF = 4

fun Attachment.isImage(): Boolean {
    return this.contentType.contains("image")
}

/**
 * Whether this attachment is an animated GIF/WebP (as opposed to a static image).
 *
 * Reads the authoritative [FLAG_GIF] bit set by the sender; falls back to the MIME type for
 * legacy / cross-client messages that never set the flag (an un-flagged `image/gif` is treated as
 * animated). An un-flagged `image/webp` cannot be classified from MIME alone, so it stays static
 * ("[Image]") per the missing-flag convention. Bitwise read only — does NOT collide with the voice
 * `flags == 1` check.
 */
fun Attachment.isAnimatedImage(): Boolean = isAnimatedImage(flags, contentType)

/**
 * Raw-value predicate backing [Attachment.isAnimatedImage], so callers holding only the primitive
 * `flags`/`contentType` (e.g. [QuotedAttachment]) can share the exact same classification.
 */
fun isAnimatedImage(flags: Int, contentType: String): Boolean =
    (flags and FLAG_GIF) != 0 || contentType.trim() == "image/gif"

fun Attachment.isVideo(): Boolean {
    return this.contentType.contains("video")
}

//是否是音频消息
fun Attachment.isAudioMessage(): Boolean {
    return flags == 1 && this.contentType.contains("audio")
}
//是否是音频文件
fun Attachment.isAudioFile(): Boolean {
    return flags == 0 && this.contentType.contains("audio")
}

/**
 * Check if the attachment is a long text file (oversized text converted to file)
 */
fun Attachment.isLongText(): Boolean {
    return this.contentType == CONTENT_TYPE_LONG_TEXT
}

enum class AttachmentStatus(val code: Int) {
    LOADING(2),
    SUCCESS(3),
    FAILED(4),
    EXPIRED(5)
}
