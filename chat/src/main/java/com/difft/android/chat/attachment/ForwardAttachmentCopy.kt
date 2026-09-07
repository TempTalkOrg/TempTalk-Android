package com.difft.android.chat.attachment

import com.difft.android.chat.media.EncryptedAttachmentAccess
import difft.android.messageserialization.model.Attachment
import difft.android.messageserialization.model.AttachmentStatus
import difft.android.messageserialization.model.Forward
import difft.android.messageserialization.model.ForwardContext
import difft.android.messageserialization.model.ForwardSourceFallback
import difft.android.messageserialization.model.Message
import org.whispersystems.signalservice.internal.push.SignalServiceProtos.Mode
import java.util.UUID

/**
 * Forwarding produces a NEW local copy of an attachment, so it must produce a new local identity:
 * the copy gets its own [Attachment.localId], hence its own directory, its own download/progress
 * state, and its own deletion scope. Server identity ([Attachment.id] / `authorityId` / `key` /
 * `digest`) is carried over verbatim — the send pipeline matches attachments by `id` when the
 * fast-path authorization rewrites the digest, and the wire payload is unchanged.
 */

/**
 * The message a forward copy is being made FROM, needed only while that context is still in hand:
 * the copy's local source file is resolved from the ORIGINAL attachment, whose identity the copy no
 * longer carries.
 *
 * @param ownerMessageId id of the message that owns the attachments being forwarded. Addressing
 *   never reads it — it travels only as the legacy-address hint the send-time materializer hands
 *   back to the migration when the current address holds nothing.
 * @param isConfidential true suppresses local copying entirely — a confidential attachment must not
 *   gain a persistent copy that outlives the ephemeral lifecycle of the message it came from.
 */
data class ForwardSourceContext(
    val ownerMessageId: String?,
    val isConfidential: Boolean
)

/** The [ForwardSourceContext] of a persisted message whose content is being forwarded. */
fun Message.forwardSourceContext(): ForwardSourceContext =
    ForwardSourceContext(id, mode == Mode.CONFIDENTIAL_VALUE)

/**
 * A fresh forward copy of this attachment: new localId, marked as a forward copy, LOADING until the
 * local file is materialized.
 *
 * With [source] the send-time copy source is resolved from THIS (still original) attachment; without
 * it, an already-captured source is carried over — which is what lets the per-target re-mint keep the
 * copy source that the original forward-build captured.
 */
fun Attachment.toForwardCopy(source: ForwardSourceContext? = null): Attachment {
    val sourceFilePath = source?.let { forwardSourceFilePath(this, it) } ?: forwardSourceFilePath
    // Nothing readable at the current address is not yet a verdict: the original's file may still be
    // sitting at a pre-per-copy address that only the migration can reach, and only off the main
    // thread. Carry what the IO-side materializer needs to ask.
    val fallback = when {
        sourceFilePath != null -> null
        source == null -> forwardSourceFallback
        source.isConfidential -> null
        else -> ForwardSourceFallback(this, source.ownerMessageId)
    }
    return copy(
        localId = UUID.randomUUID().toString(),
        status = AttachmentStatus.LOADING.code,
        isForwardCopy = true
    ).also {
        it.forwardSourceFilePath = sourceFilePath
        it.forwardSourceFallback = fallback
    }
}

/**
 * Deep copy of this forward tree with a fresh local identity minted for every attachment leaf, at
 * every nesting level. Domain objects only — files are copied separately, at send time.
 *
 * Required per TARGET message, not once per forward action: the same [ForwardContext] instance is
 * dispatched to N target conversations, and sharing one localId across them would put N messages
 * back in one directory — the very coupling per-copy addressing removes.
 */
fun ForwardContext.deepCopyWithNewAttachmentIdentities(source: ForwardSourceContext? = null): ForwardContext =
    copy(forwards = forwards?.map { it.deepCopyWithNewAttachmentIdentities(source) })

fun Forward.deepCopyWithNewAttachmentIdentities(source: ForwardSourceContext? = null): Forward =
    copy(
        attachments = attachments?.map { it.toForwardCopy(source) },
        forwards = forwards?.map { it.deepCopyWithNewAttachmentIdentities(source) },
        mentions = mentions?.toList()
    )

/**
 * The readable on-disk source to copy [attachment] from, or null when there is nothing to copy —
 * confidential source, no file name, or neither plaintext nor ciphertext readable. Every null case
 * degrades to today's behaviour: the copy stays LOADING and downloads.
 */
private fun forwardSourceFilePath(attachment: Attachment, source: ForwardSourceContext): String? {
    if (source.isConfidential) return null
    if (attachment.fileName.isNullOrEmpty()) return null
    // The ORIGINAL attachment is addressed by its own localId, exactly like the copy about to be
    // minted — no owner-message context is needed to find it. A source still sitting at a legacy
    // address simply reads as "nothing to copy": the copy stays LOADING and downloads, which is the
    // pre-existing behaviour. Never migrate here — this runs on the caller's (main) thread.
    val path = AttachmentPathResolver.fileFor(attachment)
    return path.takeIf { EncryptedAttachmentAccess.isReadable(it) }
}
