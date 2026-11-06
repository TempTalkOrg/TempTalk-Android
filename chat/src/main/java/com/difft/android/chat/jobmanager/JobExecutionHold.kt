package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.JobSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Keeps matching jobs queued without running them, for example message sends while messaging is
 * paused (#1382). A held job stays at the head of its queue, so later jobs in that queue wait too.
 */
interface JobExecutionHold {

    /**
     * True while [jobSpec] must not be picked. Called on the management dispatcher for every
     * candidate of every queue scan, so it must be cheap and must not block.
     */
    fun holds(jobSpec: JobSpec): Boolean

    /** Emits when a hold may have lifted; the job runners then scan the queue again. */
    val released: Flow<Unit>

    companion object {
        val NONE: JobExecutionHold = object : JobExecutionHold {
            override fun holds(jobSpec: JobSpec): Boolean = false
            override val released: Flow<Unit> = emptyFlow()
        }
    }
}
