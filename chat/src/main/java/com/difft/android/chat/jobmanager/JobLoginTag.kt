package com.difft.android.chat.jobmanager

/**
 * The login a job belongs to (#1382 A04). [JobManager.add] stamps every new job with it as its
 * [Job.creatorLogin]; the stamp is stored with the job's data, so it survives retries, deferrals
 * and process restarts, and a send job compares it with [current] before it sends.
 */
fun interface JobLoginTag {

    /** An opaque tag of the signed-in login (never the credential itself); null when there is none. */
    fun current(): String?

    /**
     * False while the stored login has not been read yet in this process, so a null [current]
     * does not yet mean signed out.
     */
    fun loaded(): Boolean = true

    /**
     * Suspends until [loaded], then returns the login read at that moment (null: signed out),
     * captured once and never re-read: the tag of a job added before the login loaded.
     */
    suspend fun awaitFirstLoad(): String? = current()

    companion object {
        /** No login: every job is untagged and no send job is admitted. */
        val NONE: JobLoginTag = JobLoginTag { null }
    }
}
