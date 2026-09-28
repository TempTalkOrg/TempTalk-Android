package com.difft.android.p2p.engine.session

/**
 * What the receiving side tells the session about the work it is doing, so a sender can ask
 * `GET /status` and tell a receiver that is still working from one that has stopped.
 *
 * The importer, the attachment writer and the promote step each report the one thing they know.
 * The session turns every reported change into a bump of `progressRevision`; that counter — not any
 * single figure here — is what the sender reads liveness from, because a stage can legitimately sit
 * on the same record count for a long time (a large attachment, a database checkpoint) while work
 * goes on.
 *
 * Every call is cheap and thread-safe; reporters may be called from any thread.
 */
interface TransferProgressReporter {

    /** The receiver's own name for what it is doing now. Shown to the user on the other end; never decided on. */
    fun stage(stage: String)

    /** Total records written to staging so far (monotonic; a lower value is ignored). */
    fun recordsImported(total: Long)

    /** Attachment bytes written since the last call. */
    fun attachmentBytesLanded(delta: Long)

    /**
     * The import has passed the point after which a `/cancel` can no longer undo it (the data is being
     * promoted into the live library). From here a cancel is answered `too_late` and the transfer runs
     * to its end.
     */
    fun committing()

    /** For callers that have nothing to report to. */
    object None : TransferProgressReporter {
        override fun stage(stage: String) = Unit
        override fun recordsImported(total: Long) = Unit
        override fun attachmentBytesLanded(delta: Long) = Unit
        override fun committing() = Unit
    }
}
