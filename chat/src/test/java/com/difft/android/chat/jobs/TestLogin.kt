package com.difft.android.chat.jobs

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.JobLoginTag
import kotlinx.coroutines.flow.MutableStateFlow

/** The signed-in login of the send-job tests (#1382 A04). */
internal const val TEST_LOGIN = "login-a"

/**
 * Binds this job as the job manager would: created by [creator] and run while [current] is signed
 * in. A send job only sends when the two match.
 */
internal fun <J : Job> J.bindLogin(creator: String? = TEST_LOGIN, current: String? = TEST_LOGIN): J = apply {
    creatorLogin = creator
    loginTag = JobLoginTag { current }
}

/** A [MessagingPauseJobHold] over [gate] with [login] signed in. */
internal fun testJobHold(gate: MessagingGate, login: String? = TEST_LOGIN): MessagingPauseJobHold =
    MessagingPauseJobHold(gate, MutableStateFlow(login))
