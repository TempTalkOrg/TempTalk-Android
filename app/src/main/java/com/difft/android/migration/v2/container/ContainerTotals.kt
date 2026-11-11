package com.difft.android.migration.v2.container









data class ContainerTotals(
    val recordCount: Int,
    val attachmentCount: Int,
    val attachmentBytes: Long,
) {
    
    val known: Boolean get() = recordCount > 0 || attachmentBytes > 0

    companion object {
        
        fun fromManifest(manifest: ContainerManifest): ContainerTotals? =
            ContainerTotals(manifest.recordCount, manifest.attachmentCount, manifest.attachmentBytes)
                .takeIf { it.known }
    }
}











object TransferProgress {
    
    const val RECORD_WEIGHT_BYTES = 1024L

    
    fun ratio(recordsDone: Int, attachmentBytesDone: Long, totals: ContainerTotals?): Float? {
        if (totals == null || !totals.known) return null
        val denominator = totals.recordCount * RECORD_WEIGHT_BYTES + totals.attachmentBytes
        if (denominator <= 0L) return null
        val numerator = recordsDone * RECORD_WEIGHT_BYTES + attachmentBytesDone
        return (numerator.toDouble() / denominator).coerceIn(0.0, 1.0).toFloat()
    }
}
