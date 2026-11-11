package com.difft.android.migration.v2.handoff

import android.os.StatFs
import com.difft.android.migration.v2.export.ExportEstimate
import java.io.File









object HandoffSpaceCheck {

    
    const val MARGIN_BYTES: Long = 256L * 1024 * 1024

    data class Verdict(val requiredBytes: Long, val availableBytes: Long) {
        val sufficient: Boolean get() = availableBytes >= requiredBytes

        
        val shortfallBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0)
    }

    




    fun evaluate(filesDir: File, estimate: ExportEstimate, includeAttachments: Boolean): Verdict =
        evaluate(
            estimate = estimate,
            includeAttachments = includeAttachments,
            availableBytes = StatFs(filesDir.absolutePath).availableBytes,
        )

    
    fun evaluate(estimate: ExportEstimate, includeAttachments: Boolean, availableBytes: Long): Verdict {
        val payload = if (includeAttachments) estimate.totalBytes else estimate.withoutAttachmentsBytes
        return Verdict(requiredBytes = payload + MARGIN_BYTES, availableBytes = availableBytes)
    }
}
