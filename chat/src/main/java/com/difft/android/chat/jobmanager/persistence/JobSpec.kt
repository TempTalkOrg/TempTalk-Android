package com.difft.android.chat.jobmanager.persistence

data class JobSpec(
    val id: String,
    val factoryKey: String,
    val queueKey: String?,
    val createTime: Long,
    val nextRunAttemptTime: Long,
    val runAttempt: Int,
    val maxAttempts: Int,
    val lifespan: Long,
    val serializedData: String,
    val isRunning: Boolean,
    val isMemoryOnly: Boolean
) {
    override fun toString(): String =
        "id: JOB::$id | factoryKey: $factoryKey | queueKey: $queueKey | " +
            "createTime: $createTime | nextRunAttemptTime: $nextRunAttemptTime | " +
            "runAttempt: $runAttempt | maxAttempts: $maxAttempts | lifespan: $lifespan | " +
            "isRunning: $isRunning | memoryOnly: $isMemoryOnly"
}
