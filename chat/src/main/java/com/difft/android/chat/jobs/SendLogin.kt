package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.Job

/**
 * How a send job's [Job.creatorLogin] compares with the signed-in login (#1382 A04). A job only
 * sends for the login that created it; this check is the guarantee, independent of the held sends
 * dropped when a login ends and of the one-time sweep of untagged jobs ([UntaggedSendJobSweep]).
 */
internal enum class SendLogin {
    /** Created by the signed-in login: may send. */
    CURRENT,

    /** No login is signed in, or it has not loaded yet: wait, the job is held meanwhile. */
    NONE,

    /** Created by another login, or untagged: never sends, fails permanently. */
    OTHER,
}

internal fun Job.sendLogin(): SendLogin {
    val current = currentLogin() ?: return SendLogin.NONE
    return if (creatorLogin == current) SendLogin.CURRENT else SendLogin.OTHER
}

internal fun Job.logSendSessionMismatch() {
    L.w { "[MigrationPause] event=send_session_mismatch key=${getFactoryKey()} jobId=$id tagged=${creatorLogin != null}" }
}
