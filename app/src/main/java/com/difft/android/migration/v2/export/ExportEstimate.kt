package com.difft.android.migration.v2.export








data class ExportEstimate(
    val recordCount: Int,
    val recordBytes: Long,
    val attachmentBytes: Long,
    
    val voiceNoteBytes: Long,
) {
    val totalBytes: Long get() = recordBytes + attachmentBytes

    
    val withoutAttachmentsBytes: Long get() = recordBytes + voiceNoteBytes

    



    val offersAttachmentChoice: Boolean get() = attachmentBytes > ATTACHMENT_TOGGLE_MIN_BYTES

    companion object {
        
        const val ATTACHMENT_TOGGLE_MIN_BYTES: Long = 1L shl 30
    }
}
