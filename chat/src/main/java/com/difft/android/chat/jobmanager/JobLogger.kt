package com.difft.android.chat.jobmanager

object JobLogger {

    @JvmStatic
    fun format(job: Job, event: String): String = format(job, "", event)

    @JvmStatic
    fun format(job: Job, extraTag: String, event: String): String {
        val id = job.id
        val tag = if (extraTag.isEmpty()) "" else "[$extraTag]"
        val timeSinceSubmission = System.currentTimeMillis() - job.parameters.createTime
        val runAttempt = job.runAttempt + 1
        val maxAttempts = if (job.parameters.maxAttempts == Job.Parameters.UNLIMITED) {
            "Unlimited"
        } else {
            "${job.parameters.maxAttempts}"
        }
        val lifespan = if (job.parameters.lifespan == Job.Parameters.IMMORTAL) {
            "Immortal"
        } else {
            "${job.parameters.lifespan} ms"
        }
        return "[JOB::$id][${job.javaClass.simpleName}]$tag $event " +
            "(Time Since Submission: $timeSinceSubmission ms, Lifespan: $lifespan, " +
            "Run Attempt: $runAttempt/$maxAttempts, Queue: ${job.parameters.queue})"
    }
}
