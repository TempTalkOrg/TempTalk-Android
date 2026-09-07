package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.attachment.AttachmentPathResolver
import com.difft.android.chat.attachment.AttachmentRowTarget
import com.difft.android.chat.attachment.adoptableLocalId
import com.difft.android.chat.attachment.attachmentRowTarget
import com.tencent.wcdb.base.Value
import difft.android.messageserialization.model.Attachment
import org.difft.app.database.models.DBAttachmentModel
import org.difft.app.database.synthesizedLocalId
import org.difft.app.database.wcdb
import java.io.File

/**
 * Which attachment row a send job is writing, and where that copy's bytes are.
 *
 * Extracted from [PushTextSendJob] so the job stays what it is — the send state machine. Everything
 * here answers one question: given the attachment this job carries, which row IS it? The server-side
 * id is shared by every forwarded copy of the same file, so an id-based locator can write another
 * message's row; the copy's own localId is what makes the write, the progress key, and the staged
 * source per-copy.
 *
 * One instance per job: [recoverLocalId] caches the id it recovers for a job persisted before the
 * localId column.
 *
 * All wcdb calls run on Dispatchers.IO, like the job that owns this.
 */
@Suppress("BlockingWcdbInSuspend")
internal class AttachmentSendIdentity(private val messageId: String) {

    /**
     * The local id [recoverLocalId] recovered for a job persisted before the localId column. Held
     * here rather than written back into the attachment, whose `localId` is a val: the effect is the
     * same, since every locator and every progress emit reads it through [localIdOf].
     */
    private var recoveredLocalId: String? = null

    fun localIdOf(attachment: Attachment): String? {
        val localId: String? = attachment.localId
        return localId?.takeIf { it.isNotEmpty() } ?: recoveredLocalId
    }

    /**
     * Recovers this copy's local id for a job persisted before the localId column, so the progress
     * key and every row write are per-copy addressed from here on.
     *
     * Without it such a job emits progress under the message id while the bubble collects under the
     * row's own (hydrated or synthesized) local id — two different keys, so an upload still running
     * across an upgrade never feeds its spinner. Located by the only identifiers the job carries, and
     * taken only when that pair names exactly ONE row; a row with no id yet is given its synthesized
     * one, the same value the migration's backfill writes.
     *
     * Null when no single row is named — the caller then keeps the pre-localId key.
     */
    fun recoverLocalId(attachment: Attachment): String? {
        if (attachment.id.isEmpty()) return null
        val candidates = wcdb.attachment.getAllObjects(
            DBAttachmentModel.id.eq(attachment.id).and(DBAttachmentModel.messageId.eq(messageId))
        )
        val recovered = adoptableLocalId(candidates, { it.localId }, { it.synthesizedLocalId() }) ?: return null
        val row = candidates.single()
        if (row.localId.isNullOrEmpty()) {
            wcdb.attachment.updateValue(
                recovered,
                DBAttachmentModel.localId,
                DBAttachmentModel.databaseId.eq(row.databaseId)
            )
        }
        recoveredLocalId = recovered
        L.i { "[PushTextSendJob] recovered attachment localId for legacy job, messageId=$messageId" }
        return recovered
    }

    /**
     * Update attachment with all relevant fields (authorityId, key, digest, status).
     * Uses updateRow instead of DELETE + INSERT to avoid WCDB soft-delete issues.
     *
     * Located by the copy's own localId: the server-side id is shared by every forwarded copy of the
     * same file, so an id-based locator can write another message's row.
     */
    fun updateRow(attachment: Attachment) {
        wcdb.attachment.updateRow(
            arrayOf(
                Value(attachment.authorityId),
                Value(attachment.key),
                Value(attachment.digest),
                Value(attachment.status)
            ),
            arrayOf(
                DBAttachmentModel.authorityId,
                DBAttachmentModel.key,
                DBAttachmentModel.digest,
                DBAttachmentModel.status
            ),
            rowCondition(attachment)
        )
    }

    /**
     * Row locator for [attachment]. A job persisted before the localId column deserializes without
     * one (gson bypasses the constructor default), so an in-flight upgrade degrades to the legacy
     * id+messageId pair rather than writing with a null key.
     */
    private fun rowCondition(attachment: Attachment) =
        when (val target = attachmentRowTarget(localIdOf(attachment), attachment.id, messageId)) {
            is AttachmentRowTarget.ByLocalId -> DBAttachmentModel.localId.eq(target.localId)
            is AttachmentRowTarget.ByIdAndMessage ->
                DBAttachmentModel.id.eq(target.attachmentId).and(DBAttachmentModel.messageId.eq(target.messageId))

            is AttachmentRowTarget.ById -> DBAttachmentModel.id.eq(target.attachmentId)
        }

    /**
     * Upload source for a message re-read from the DB (resend), which carries no transient
     * [Attachment.path]. An already-uploaded attachment (non-zero authorityId) needs no source —
     * the persisted pointer is sent as-is, so null is returned and the upload is skipped. A
     * never-uploaded attachment recovers the staged file; when that file is gone the send MUST
     * fail: proceeding would deliver a dead pointer (authorityId=0, empty key/digest) that renders
     * as a permanently broken attachment for the recipient.
     *
     * The address comes from the resolver's MIGRATING read: a staged file written before per-copy
     * addressing still sits under the owner message's directory, and [AttachmentPathResolver]'s
     * migrator gets the chance to bring it to the current address first (this job runs on IO, so
     * the blocking call is allowed). Routing through the resolver instead of probing the legacy path
     * by hand keeps the migration the only component that knows legacy addresses, puts the recovered
     * bytes where every OTHER reader looks (including the ciphertext this upload writes next to its
     * source), and retires by itself once the migration completes.
     */
    fun resendSourcePath(attachment: Attachment): String? {
        if (attachment.authorityId != 0L) return null
        if (!attachment.fileName.isNullOrEmpty()) {
            val staged = File(AttachmentPathResolver.materializedFileFor(attachment, messageId))
            if (staged.isFile && staged.length() > 0) return staged.path
        }
        throw IllegalStateException(
            "attachment never uploaded and staged source missing messageId=$messageId"
        )
    }
}
