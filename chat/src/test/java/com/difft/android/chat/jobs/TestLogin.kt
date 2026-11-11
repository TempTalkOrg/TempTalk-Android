package com.difft.android.chat.jobs

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.JobLoginTag
import kotlinx.coroutines.flow.MutableStateFlow


internal const val TEST_LOGIN = "login-a"





internal fun <J : Job> J.bindLogin(creator: String? = TEST_LOGIN, current: String? = TEST_LOGIN): J = apply {
    creatorLogin = creator
    loginTag = JobLoginTag { current }
}


internal fun testJobHold(gate: MessagingGate, login: String? = TEST_LOGIN): MessagingPauseJobHold =
    MessagingPauseJobHold(gate, MutableStateFlow(login))
