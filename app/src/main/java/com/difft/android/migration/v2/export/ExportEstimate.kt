package com.difft.android.migration.v2.export

/**
 * What a send would carry, shown on the sender's pairing page before the transfer starts.
 *
 * Attachment figures are exact at-rest file lengths (the same walk that fills the manifest totals);
 * [recordBytes] is an estimate — a sample of encoded records times the record count — because the
 * only exact figure would come from running the whole export.
 */
data class ExportEstimate(
    val recordCount: Int,
    val recordBytes: Long,
    val attachmentBytes: Long,
    /** Bytes of the voice notes among [attachmentBytes]; they travel even when attachments are left behind. */
    val voiceNoteBytes: Long,
) {
    val totalBytes: Long get() = recordBytes + attachmentBytes

    /** What travels with attachments left behind: the records plus the voice notes. */
    val withoutAttachmentsBytes: Long get() = recordBytes + voiceNoteBytes

    /**
     * Whether the user is asked about attachments at all: below the threshold they simply travel and
     * the page shows no switch; above it the switch appears, defaulting to on.
     */
    val offersAttachmentChoice: Boolean get() = attachmentBytes > ATTACHMENT_TOGGLE_MIN_BYTES

    companion object {
        /** 1 GiB — the same constant on every platform, so the switch appears on the same accounts everywhere. */
        const val ATTACHMENT_TOGGLE_MIN_BYTES: Long = 1L shl 30
    }
}
