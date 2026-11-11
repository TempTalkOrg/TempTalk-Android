package com.difft.android.chat.jobs

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.chat.jobmanager.Data






internal class ProbeSendJob(
    gate: MessagingGate,
    maxAttempts: Int = 3,
    private val send: suspend (MessagingTicket) -> Unit = {},
) : PushSendJob(Parameters.Builder().setQueue("probe").setMaxAttempts(maxAttempts).build(), gate) {

    init {
        
        bindLogin()
    }

    
    @Volatile
    var started = false
        private set

    @Volatile
    var sends = 0
        private set

    @Volatile
    var lastTicket: MessagingTicket? = null
        private set

    
    var beforeAdmit: () -> Unit = {}

    public override suspend fun onPushSend() {
        started = true
        beforeAdmit()
        val admission = admitSend() ?: return
        lastTicket = admission
        sends++
        send(admission)
    }

    override fun serialize(): Data = Data.Builder().build()

    override fun getFactoryKey(): String = PushTextSendJob.KEY

    override fun onAdded() = Unit

    
    @Volatile
    var failures = 0
        private set

    override fun onFailure() {
        failures++
    }
}
