package com.difft.android.chat.jobs

import com.difft.android.base.migration.pause.MessagingGate
import com.difft.android.base.migration.pause.MessagingTicket
import com.difft.android.chat.jobmanager.Data

/**
 * A minimal send job for the migration message pause tests (#1382), bound to [TEST_LOGIN]: admits
 * its send exactly where a real send job does and records what it sent, with [send] standing in
 * for the network call.
 */
internal class ProbeSendJob(
    gate: MessagingGate,
    maxAttempts: Int = 3,
    private val send: suspend (MessagingTicket) -> Unit = {},
) : PushSendJob(Parameters.Builder().setQueue("probe").setMaxAttempts(maxAttempts).build(), gate) {

    init {
        // Created and run by the same login unless a test rebinds it (#1382 A04).
        bindLogin()
    }

    /** Whether [onPushSend] started, i.e. the job got past the login check at the start of its run. */
    @Volatile
    var started = false
        private set

    @Volatile
    var sends = 0
        private set

    @Volatile
    var lastTicket: MessagingTicket? = null
        private set

    /** Runs once the work has started, right before the send is admitted. */
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

    /** How often [onFailure] ran: where a real send job marks its message failed. */
    @Volatile
    var failures = 0
        private set

    override fun onFailure() {
        failures++
    }
}
