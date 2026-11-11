package com.difft.android.chat.jobs

import com.difft.android.base.log.lumberjack.L
import com.difft.android.chat.jobmanager.Job






internal enum class SendLogin {
    
    CURRENT,

    
    NONE,

    
    OTHER,
}

internal fun Job.sendLogin(): SendLogin {
    val current = currentLogin() ?: return SendLogin.NONE
    return if (creatorLogin == current) SendLogin.CURRENT else SendLogin.OTHER
}

internal fun Job.logSendSessionMismatch() {
    L.w { "[MigrationPause] event=send_session_mismatch key=${getFactoryKey()} jobId=$id tagged=${creatorLogin != null}" }
}
