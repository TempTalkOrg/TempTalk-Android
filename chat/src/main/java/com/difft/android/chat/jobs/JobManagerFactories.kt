package com.difft.android.chat.jobs

import android.app.Application
import com.difft.android.chat.jobmanager.Constraint
import com.difft.android.chat.jobmanager.ConstraintObserver
import com.difft.android.chat.jobmanager.Job
import com.difft.android.chat.jobmanager.impl.NetworkConstraint
import com.difft.android.chat.jobmanager.impl.NetworkConstraintObserver

object JobManagerFactories {

    @JvmStatic
    fun getJobFactories(application: Application): Map<String, Job.Factory<*>> = mapOf(
        PushTextSendJob.KEY to PushTextSendJob.Factory(),
        PushReactionSendJob.KEY to PushReactionSendJob.Factory(),
        DownloadAttachmentJob.KEY to DownloadAttachmentJob.Factory(),
        PushReadReceiptSendJob.KEY to PushReadReceiptSendJob.Factory(),
        PushGroupKeySendJob.KEY to PushGroupKeySendJob.Factory(),
        PushForwardNoticeSendJob.KEY to PushForwardNoticeSendJob.Factory()
    )

    @JvmStatic
    fun getConstraintFactories(application: Application): Map<String, Constraint.Factory<*>> = mapOf(
        NetworkConstraint.KEY to NetworkConstraint.Factory(application)
    )

    @JvmStatic
    fun getConstraintObservers(application: Application): List<ConstraintObserver> = listOf(
        NetworkConstraintObserver(application)
    )
}
