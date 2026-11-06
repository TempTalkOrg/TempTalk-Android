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
     * The live session whose upload body has started; null when there is none. An import reads it
     * once when it starts and passes it to [committing], so its commit is bound to the session that
     * delivered its bytes.
     */
    fun bodySessionId(): String?

    /**
     * The import of [sessionId] has passed the point after which a `/cancel` can no longer undo it
     * (the data is being promoted into the live library). From here a cancel is answered `too_late`
     * and the transfer runs to its end.
     *
     * Returns false unless [sessionId] is still the live session and its upload body started: when it
     * was cancelled or ended, even if a newer session is live now, the caller must abort without
     * promoting, because the sender may have been told `stopped` or `rolled_back` (#1382).
     */
    fun committing(sessionId: String?): Boolean

    /** For callers that have nothing to report to. */
    object None : TransferProgressReporter {
        override fun stage(stage: String) = Unit
        override fun recordsImported(total: Long) = Unit
        override fun attachmentBytesLanded(delta: Long) = Unit
        override fun bodySessionId(): String? = null
        override fun committing(sessionId: String?) = true
    }
}
