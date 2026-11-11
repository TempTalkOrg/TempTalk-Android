package com.difft.android.chat.jobmanager

import com.difft.android.chat.jobmanager.persistence.JobSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow





interface JobExecutionHold {

    



    fun holds(jobSpec: JobSpec): Boolean

    
    val released: Flow<Unit>

    companion object {
        val NONE: JobExecutionHold = object : JobExecutionHold {
            override fun holds(jobSpec: JobSpec): Boolean = false
            override val released: Flow<Unit> = emptyFlow()
        }
    }
}
