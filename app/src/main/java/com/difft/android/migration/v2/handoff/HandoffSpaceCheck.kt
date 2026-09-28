package com.difft.android.migration.v2.handoff

import android.os.StatFs
import com.difft.android.migration.v2.export.ExportEstimate
import java.io.File

/**
 * Same-device storage pre-check: can this device hold the transfer that is about to start?
 *
 * Both packages live on the same storage volume, so the sender can answer this for the receiver
 * before it goes offline. The margin exists because promote moves rather than copies, but the
 * staging database and the real one coexist until the move completes, so the peak requirement is
 * the payload plus working room.
 */
object HandoffSpaceCheck {

    /** Working room for the staging-vs-real overlap during promote. */
    const val MARGIN_BYTES: Long = 256L * 1024 * 1024

    data class Verdict(val requiredBytes: Long, val availableBytes: Long) {
        val sufficient: Boolean get() = availableBytes >= requiredBytes

        /** How much more the user has to free; 0 when the payload already fits. */
        val shortfallBytes: Long get() = (requiredBytes - availableBytes).coerceAtLeast(0)
    }

    /**
     * @param filesDir any path on the volume the migration writes to (the app's own files dir).
     * @param includeAttachments the user's current attachment-toggle choice; leaving attachments
     *   behind is the remedy the UI offers when the payload does not fit.
     */
    fun evaluate(filesDir: File, estimate: ExportEstimate, includeAttachments: Boolean): Verdict =
        evaluate(
            estimate = estimate,
            includeAttachments = includeAttachments,
            availableBytes = StatFs(filesDir.absolutePath).availableBytes,
        )

    /** [evaluate] with the free-space figure supplied, so tests need no real volume. */
    fun evaluate(estimate: ExportEstimate, includeAttachments: Boolean, availableBytes: Long): Verdict {
        val payload = if (includeAttachments) estimate.totalBytes else estimate.withoutAttachmentsBytes
        return Verdict(requiredBytes = payload + MARGIN_BYTES, availableBytes = availableBytes)
    }
}
