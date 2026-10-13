package com.difft.android.p2p.engine.model

/**
 * The two ways a scanned pairing payload can be used, named from the scanner's side so the value and
 * the endpoint pair it selects read the same.
 */
enum class TransferDirection(val wire: String) {
    /** The scanner sends: `prepare-upload` / `upload`. The default when a payload carries no `dir`. */
    UPLOAD("upload"),

    /**
     * The displayer holds the data: the scanner reverse-pairs (`reverse-pair`) and receives it as an
     * ordinary upload. Still exchanged as `"download"` for compatibility with codes minted by
     * releases that pulled instead.
     */
    DOWNLOAD("download");

    companion object {
        /** Absent or unrecognised → [UPLOAD]. */
        fun of(dir: String?): TransferDirection =
            entries.firstOrNull { it.wire == dir?.trim()?.lowercase() } ?: UPLOAD
    }
}

/** Direction of a pairing payload; see [TransferDirection.of]. */
val QrPayload.direction: TransferDirection
    get() = TransferDirection.of(dir)
