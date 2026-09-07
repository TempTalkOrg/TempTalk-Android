package com.difft.android.chat.attachment

import com.difft.android.base.utils.FileUtil
import difft.android.messageserialization.model.Attachment

/**
 * Single place that turns "this attachment" into its on-disk location.
 *
 * ONE rule: every attachment — forwarded, quoted or a plain message's own — lives under its own
 * [Attachment.localId]. Each copy therefore owns a private directory, so deleting one copy can never
 * remove another message's file, and a copy's download state is its own.
 *
 * The owning message's id takes no part in addressing. It survives only as a MIGRATION hint: a file
 * written before per-copy addressing may still sit under the owner message's directory, and that is
 * the one thing [Migrator] needs to know to bring it across. Addressing never reads it — which is why
 * it is not a parameter of [fileFor] / [directoryFor] at all, only of [materializedFileFor].
 *
 * Picking WHICH attachment of a message tree to address stays with the caller — this object only
 * answers WHERE. Nesting depth does not change addressing.
 *
 * This resolver knows only the CURRENT layout; no legacy path shape is encoded here.
 */
object AttachmentPathResolver {

    /**
     * Moves a single attachment from wherever it historically lived to the address this resolver
     * reports. Implemented by the forward-attachment migration, which is the only component allowed
     * to know legacy addresses; it stays live for as long as that module ships, because its bulk
     * pass completing is not a proof that no legacy file is left.
     */
    fun interface Migrator {
        /**
         * Materializes [attachment]'s file at [targetDirectory] if it is not there yet.
         * Blocking IO — callers must be off the main thread. Returns true when the file is present
         * afterwards.
         *
         * @param legacyOwnerMessageId id of the message that owns [attachment], the directory a
         *   pre-migration write may have used. Null when the caller has no such context.
         */
        fun migrateIfNeeded(attachment: Attachment, targetDirectory: String, legacyOwnerMessageId: String?): Boolean
    }

    /** Registered once at startup by the migration module; null means "nothing to migrate". */
    @Volatile
    var migrator: Migrator? = null

    /**
     * The directory key of [attachment] — also the segment used by exported attachment content uris,
     * which is why it is exposed rather than only the assembled path.
     */
    fun directoryKeyFor(attachment: Attachment): String = attachment.localId

    /**
     * Directory key of a persisted attachment row, or null when the row cannot name one (a row
     * written before the localId column existed). Mirrors [directoryKeyFor] for callers that hold a
     * DB row rather than a domain object — notably the content provider, which must recompute the
     * served path from the matched row instead of trusting the uri's literal segment.
     */
    fun directoryKeyForRow(localId: String?): String? = localId?.takeIf { it.isNotEmpty() }

    /** Directory holding [attachment]'s file, with a trailing separator. */
    fun directoryFor(attachment: Attachment): String =
        FileUtil.getMessageAttachmentFilePath(directoryKeyFor(attachment))

    /**
     * Full path of [attachment]'s file. A null `fileName` yields the bare directory path — same as
     * the hand-rolled `path + fileName` concatenations this replaces.
     */
    fun fileFor(attachment: Attachment): String =
        directoryFor(attachment) + (attachment.fileName ?: "")

    /**
     * Where a NEW attachment's bytes must be written, given the identity it is about to carry.
     *
     * A writer does not yet hold an [Attachment] — it stages the file first and builds the row from
     * it — so it cannot ask [fileFor]. Going through here instead of assembling the path by hand is
     * what keeps writers and readers on one rule: every historical "attachment lands somewhere the
     * reader does not look" bug came from a hand-rolled `getMessageAttachmentFilePath(messageId)` at
     * a write site. The caller MUST persist [localId] on the resulting attachment.
     */
    fun stagingFileFor(localId: String, fileName: String): String =
        FileUtil.getMessageAttachmentFilePath(localId) + fileName

    /**
     * [fileFor], having first given the registered [migrator] a chance to bring a legacy-addressed
     * file to the current address. Blocking IO — never call this from the main thread; main-thread
     * read gates use [fileFor] and fall back to the existing download path on a miss.
     *
     * @param legacyOwnerMessageId the owner message id, passed STRAIGHT THROUGH to the migrator as a
     *   legacy-address hint. It never influences the returned path.
     */
    fun materializedFileFor(attachment: Attachment, legacyOwnerMessageId: String?): String {
        migrator?.migrateIfNeeded(attachment, directoryFor(attachment), legacyOwnerMessageId)
        return fileFor(attachment)
    }
}
