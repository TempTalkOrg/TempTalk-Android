package com.difft.android.base.migration.pause

/**
 * The queued send jobs the migration message pause holds (#1382). When the login a pause belongs
 * to ends, they are dropped while messaging is still closed (iOS cancels pending sends of another
 * login session). Cleanup only: each send job is tagged with the login that created it and is
 * refused at its send under any other login (A04), so a job this drop misses never sends either.
 * Bound in :chat.
 */
fun interface HeldSendJobs {
    /**
     * Fails every queued send job not created by [keepLogin] (a [MigrationPauseScope.credentialHash],
     * or null to keep none), and returns once they are removed. Untagged ones are always dropped.
     */
    suspend fun dropAll(reason: String, keepLogin: String?)
}
