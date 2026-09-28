package com.difft.android.migration.v2.container

/**
 * What a container will hold, counted before its first byte is written and carried in the manifest
 * so the receiver can drive a determinate bar from the very first frame.
 *
 * Records and attachment payload are the only two quantities both ends can measure exactly as the
 * stream goes by. The container's total byte length is not one of them: the stream is chunked and
 * never spooled, so the sender never knows it.
 */
data class ContainerTotals(
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
) {
    /** False when the source wrote no counts (an exporter predating them): show indeterminate. */
    val known: Boolean get() = recordCount > 0 || attachmentBytes > 0

    companion object {
        /** The totals a source wrote into its manifest, or null when it wrote none. */
        fun fromManifest(manifest: ContainerManifest): ContainerTotals? =
            ContainerTotals(manifest.recordCount, manifest.attachmentCount, manifest.attachmentBytes)
                .takeIf { it.known }
    }
}

/**
 * The one progress formula both ends of a transfer compute, so their bars agree and both reach
 * exactly 100% when the stream ends:
 *
 * `(recordsDone × W + attachmentBytesDone) / (recordCount × W + attachmentBytes)`
 *
 * Records and attachment bytes are put on one scale by weighting each record as [RECORD_WEIGHT_BYTES]
 * of payload. The weight only shapes pacing between the record phase and the attachment phase; it can
 * never move the endpoint, because both terms hit their totals together at the end of the stream.
 */
object TransferProgress {
    /** Nominal payload of one record — the order of a framed JSONL message line. Pacing only. */
    const val RECORD_WEIGHT_BYTES = 1024L

    /** Progress in `[0, 1]`, or null when [totals] are unknown (the caller shows indeterminate). */
    fun ratio(recordsDone: Int, attachmentBytesDone: Long, totals: ContainerTotals?): Float? {
        if (totals == null || !totals.known) return null
        val denominator = totals.recordCount * RECORD_WEIGHT_BYTES + totals.attachmentBytes
        if (denominator <= 0L) return null
        val numerator = recordsDone * RECORD_WEIGHT_BYTES + attachmentBytesDone
        return (numerator.toDouble() / denominator).coerceIn(0.0, 1.0).toFloat()
    }
}
